package com.jbooktrader.platform.optimizer;

import com.jbooktrader.platform.backtest.*;
import com.jbooktrader.platform.marketbook.*;
import com.jbooktrader.platform.model.*;
import com.jbooktrader.platform.preferences.*;
import com.jbooktrader.platform.report.*;
import com.jbooktrader.platform.strategy.*;
import com.jbooktrader.platform.util.format.*;
import com.jbooktrader.platform.util.ui.*;

import java.io.*;
import java.lang.reflect.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static com.jbooktrader.platform.optimizer.PerformanceMetric.*;

/**
 * Runs a trading strategy in the optimizer mode using a data file containing
 * historical market snapshots.
 *
 * @author Eugene Kononov
 */
public abstract class OptimizerRunner implements Runnable {
    private static final int MAX_SAVED_RESULTS = 100;// max number of results in the optimization results file
    private static final int MAX_STABILITY_RESULTS = 10000;// max number of results in the optimization results file
    protected final List<OptimizationResult> optimizationResults;
    protected final StrategyParams strategyParams;
    protected final AtomicBoolean cancelled;
    protected final int availableProcessors;
    private final ScheduledExecutorService progressExecutor;
    private final Constructor<?> strategyConstructor;
    private final CompletionService<List<OptimizationResult>> completionService;
    private final NumberFormat nf2, nf0, gnf0;
    private final String strategyName;
    private final int minTrades;
    private final AtomicLong completedSteps;
    private final OptimizerDialog optimizerDialog;
    private final int strategiesPerProcessor;
    protected long snapshotCount;
    private ExecutorService optimizationExecutor;
    private ResultComparator resultComparator;
    private ComputationalTimeEstimator timeEstimator;
    private List<MarketSnapshot> snapshots;
    private long totalSteps;
    private String totalStrategiesString;

    protected OptimizerRunner(OptimizerDialog optimizerDialog, Strategy strategy, StrategyParams params) throws JBookTraderException {
        this.optimizerDialog = optimizerDialog;
        strategyName = strategy.getName();
        strategyParams = params;
        optimizationResults = Collections.synchronizedList(new ArrayList<OptimizationResult>());
        nf2 = NumberFormatterFactory.getNumberFormatter(2);
        nf0 = NumberFormatterFactory.getNumberFormatter(0);
        gnf0 = NumberFormatterFactory.getNumberFormatter(0, true);
        availableProcessors = Runtime.getRuntime().availableProcessors();
        completedSteps = new AtomicLong();
        cancelled = new AtomicBoolean();

        Class<?> clazz;
        try {
            clazz = Class.forName(strategy.getClass().getName());
        } catch (ClassNotFoundException cnfe) {
            throw new JBookTraderException("Could not find class " + strategy.getClass().getName());
        }

        try {
            strategyConstructor = clazz.getConstructor(new Class[]{StrategyParams.class});
        } catch (NoSuchMethodException nsme) {
            throw new JBookTraderException("Could not find strategy constructor for " + strategy.getClass().getName());
        }

        resultComparator = new ResultComparator(optimizerDialog.getSelectionCriteria());
        minTrades = optimizerDialog.getMinTrades();
        progressExecutor = Executors.newSingleThreadScheduledExecutor();
        optimizationExecutor = Executors.newFixedThreadPool(availableProcessors);
        completionService = new ExecutorCompletionService<List<OptimizationResult>>(optimizationExecutor);
        strategiesPerProcessor = PreferencesHolder.getInstance().getInt(JBTPreferences.StrategiesPerProcessor);
    }

    public Strategy getStrategyInstance(StrategyParams params) throws JBookTraderException {
        try {
            return (Strategy) strategyConstructor.newInstance(params);
        } catch (InvocationTargetException ite) {
            throw new JBookTraderException(ite.getCause());
        } catch (Exception e) {
            throw new JBookTraderException(e);
        }
    }

    protected abstract void optimize() throws JBookTraderException;

    protected void setTotalSteps(long totalSteps) {
        this.totalSteps = totalSteps;
        if (timeEstimator == null) {
            timeEstimator = new ComputationalTimeEstimator(System.currentTimeMillis(), totalSteps);
        }
        timeEstimator.setTotalIterations(totalSteps);
    }

    protected void setTotalStrategies(long totalStrategies) {
        totalStrategiesString = gnf0.format(totalStrategies);
    }

    public int getMinTrades() {
        return minTrades;
    }

    public List<MarketSnapshot> getSnapshots() {
        return snapshots;
    }

    public void rateStability() {
        int maxResults = Math.min(MAX_STABILITY_RESULTS, optimizationResults.size());
        if (maxResults == 0) {
            return;
        }

        PerformanceMetric performanceMetric = optimizerDialog.getSelectionCriteria();

        for (int resultIndex = 0; resultIndex < maxResults; resultIndex++) {
            OptimizationResult candidateResult = optimizationResults.get(resultIndex);
            double candidatePerformance = candidateResult.get(performanceMetric);
            List<StrategyParam> candidateParams = candidateResult.getParams().getAll();
            double aveNeighborPerformance = 0;
            int neighbors = 0;

            for (OptimizationResult neighborResult : optimizationResults) {
                List<StrategyParam> neighborParams = neighborResult.getParams().getAll();
                boolean isCloseNeighbor = true;

                for (int index = 0; index < candidateParams.size(); index++) {
                    int neighborValue = neighborParams.get(index).getValue();
                    int candidateValue = candidateParams.get(index).getValue();
                    double bandwidth = Math.abs(candidateValue * 0.1);

                    double distance = Math.abs(candidateValue - neighborValue);
                    if (distance > bandwidth) {
                        isCloseNeighbor = false;
                        break;
                    }
                }

                if (isCloseNeighbor) {
                    neighbors++;
                    aveNeighborPerformance += neighborResult.get(performanceMetric);
                }
            }

            if (neighbors > 1) {
                aveNeighborPerformance /= neighbors;
                double stability = 100 * (aveNeighborPerformance / candidatePerformance);
                candidateResult.setStability(stability);
            }
        }
    }

    void execute(Queue<StrategyParams> tasks) throws JBookTraderException {
        int workerLoad = Math.min(strategiesPerProcessor, Math.max(1, tasks.size() / availableProcessors));
        int submittedWorkers = 0;

        while (!tasks.isEmpty()) {
            List<StrategyParams> workerTasks = new ArrayList<StrategyParams>();
            while (!tasks.isEmpty() && workerTasks.size() < workerLoad) {
                workerTasks.add(tasks.remove());
            }
            completionService.submit(new OptimizerWorker(this, workerTasks));
            submittedWorkers++;
        }

        try {
            if (submittedWorkers > 0) {
                int divider = Math.max(1, submittedWorkers / 10);
                for (int worker = 0; worker < submittedWorkers; worker++) {
                    List<OptimizationResult> results = completionService.take().get();
                    optimizationResults.addAll(results);

                    if (worker % divider == 0) {
                        Collections.sort(optimizationResults, resultComparator);
                        optimizerDialog.setResults(optimizationResults);
                    }
                }
                Collections.sort(optimizationResults, resultComparator);
                optimizerDialog.setResults(optimizationResults);
            }

        } catch (Exception e) {
            throw new JBookTraderException(e.getMessage(), e);
        }
    }

    public void cancel() {
        optimizerDialog.setProgress("Stopping optimization...");
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    private void saveToFile() throws IOException {
        if (optimizationResults.isEmpty()) {
            return;
        }

        String fileName = strategyName + "Optimizer";
        OptimizationReport optimizationReport = new OptimizationReport(fileName);

        optimizationReport.reportDescription("Strategy parameters:");
        for (StrategyParam param : strategyParams.getAll()) {
            optimizationReport.reportDescription(param.toString());
        }
        optimizationReport.reportDescription("Minimum trades for strategy inclusion: " + optimizerDialog.getMinTrades());
        optimizationReport.reportDescription("Back data file: " + optimizerDialog.getFileName());

        List<String> otpimizerReportHeaders = new ArrayList<String>();
        StrategyParams params = optimizationResults.iterator().next().getParams();
        for (StrategyParam param : params.getAll()) {
            otpimizerReportHeaders.add(param.getName());
        }

        for (PerformanceMetric performanceMetric : PerformanceMetric.values()) {
            otpimizerReportHeaders.add(performanceMetric.getName());
        }
        optimizationReport.reportHeaders(otpimizerReportHeaders);

        int maxIndex = Math.min(MAX_SAVED_RESULTS, optimizationResults.size());
        for (int index = 0; index < maxIndex; index++) {
            OptimizationResult optimizationResult = optimizationResults.get(index);
            params = optimizationResult.getParams();

            List<String> columns = new ArrayList<String>();
            for (StrategyParam param : params.getAll()) {
                columns.add(nf0.format(param.getValue()));
            }

            columns.add(nf0.format(optimizationResult.get(Trades)));
            columns.add(nf0.format(optimizationResult.get(Duration)));
            columns.add(nf0.format(optimizationResult.get(Bias)));
            columns.add(nf2.format(optimizationResult.get(PF)));
            columns.add(nf2.format(optimizationResult.get(PI)));
            columns.add(nf0.format(optimizationResult.get(Kelly)));
            columns.add(nf0.format(optimizationResult.get(CPI)));
            columns.add(nf0.format(optimizationResult.get(Stability)));
            columns.add(nf0.format(optimizationResult.get(MaxDD)));
            columns.add(nf0.format(optimizationResult.get(NetProfit)));

            optimizationReport.report(columns);
        }

    }

    private void showProgress(long counter, String text) {
        optimizerDialog.setProgress(counter, totalSteps, text);
        String remainingTime = (counter >= totalSteps) ? "00:00:00" : timeEstimator.getTimeLeft(counter);
        optimizerDialog.setRemainingTime(remainingTime);
    }

    public void iterationsCompleted(long iterationsCompleted) {
        completedSteps.getAndAdd(iterationsCompleted);
    }

    protected Queue<StrategyParams> getTasks(StrategyParams params) {
        for (StrategyParam param : params.getAll()) {
            param.setValue(param.getMin());
        }

        Queue<StrategyParams> tasks = new LinkedBlockingQueue<StrategyParams>();

        boolean allTasksAssigned = false;
        while (!allTasksAssigned && !cancelled.get()) {
            StrategyParams strategyParamsCopy = new StrategyParams(params);
            tasks.add(strategyParamsCopy);

            StrategyParam lastParam = params.get(params.size() - 1);
            lastParam.setValue(lastParam.getValue() + lastParam.getStep());

            for (int paramNumber = params.size() - 1; paramNumber >= 0; paramNumber--) {
                StrategyParam param = params.get(paramNumber);
                if (param.getValue() > param.getMax()) {
                    param.setValue(param.getMin());
                    if (paramNumber == 0) {
                        allTasksAssigned = true;
                        break;
                    }
                    int prevParamNumber = paramNumber - 1;
                    StrategyParam prevParam = params.get(prevParamNumber);
                    prevParam.setValue(prevParam.getValue() + prevParam.getStep());
                }
            }
        }

        return tasks;
    }

    public void run() {
        try {
            optimizationResults.clear();
            optimizerDialog.setResults(optimizationResults);
            optimizerDialog.enableProgress();
            BackTestFileReader backTestFileReader = new BackTestFileReader(optimizerDialog.getFileName(), optimizerDialog.getDateFilter());
            optimizerDialog.setProgress("Loading historical data file...");
            snapshots = backTestFileReader.load(optimizerDialog);
            snapshotCount = snapshots.size();

            optimizerDialog.setProgress("Starting optimization ...");
            progressExecutor.scheduleWithFixedDelay(new ProgressRunner(), 0, 1, TimeUnit.SECONDS);
            long start = System.currentTimeMillis();
            optimize();
            optimizerDialog.setProgress("Rating the stability of the top optimization results ...");
            rateStability();
            progressExecutor.shutdown();

            if (!cancelled.get()) {
                optimizerDialog.setProgress("Setting optimization results ...");
                optimizerDialog.setResults(optimizationResults);
                optimizerDialog.setProgress("Saving optimization results ...");
                saveToFile();
                long end = System.currentTimeMillis();
                long totalTimeInSecs = (end - start) / 1000;
                showProgress(totalSteps, "Optimization");
                optimizerDialog.showMessage("Optimization completed successfully in " + totalTimeInSecs + " seconds.");
            }
        } catch (Throwable t) {
            MessageDialog.showException(t);
        } finally {
            progressExecutor.shutdownNow();
            optimizationExecutor.shutdownNow();
            optimizerDialog.signalCompleted();
        }
    }

    private class ProgressRunner implements Runnable {
        public void run() {
            if (!isCancelled()) {
                long completed = completedSteps.get();
                if (completed > 0) {
                    showProgress(completed, "Optimizing " + totalStrategiesString + " strategies");
                }
            }
        }
    }
}
