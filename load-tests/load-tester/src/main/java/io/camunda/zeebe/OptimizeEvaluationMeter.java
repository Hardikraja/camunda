/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.zeebe;

import io.camunda.zeebe.config.OptimizeCfg;
import io.camunda.zeebe.util.logging.ThrottledLogger;
import io.camunda.zeebe.util.micrometer.MicrometerUtil;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OptimizeEvaluationMeter implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(OptimizeEvaluationMeter.class);
  private static final Logger THROTTLED_LOGGER =
      new ThrottledLogger(
          LoggerFactory.getLogger(OptimizeEvaluationMeter.class), Duration.ofSeconds(5));

  private final MeterRegistry registry;
  private final OptimizeCfg optimizeCfg;
  private final OptimizeReportLoadTester optimizeLoadTester;
  private final ScheduledExecutorService executorService;

  // Dashboard metrics
  private final Timer dashboardResponseTimer;
  private final Timer maxReportResponseTimer;
  private final Timer homepageLoadTimer;
  private final Counter dashboardSuccessCounter;
  private final Counter dashboardErrorCounter;

  // Benchmark metrics
  private final Timer benchmarkDashboardResponseTimer;
  private final Timer benchmarkMaxReportEvaluationTimer;
  private final Timer benchmarkMaxDetailedEvaluationTimer;
  private final Timer benchmarkTotalLoadTimer;
  private final Counter benchmarkDashboardSuccessCounter;
  private final Counter benchmarkDashboardErrorCounter;

  private ScheduledFuture<?> scheduledTask;

  public OptimizeEvaluationMeter(final MeterRegistry registry, final OptimizeCfg optimizeCfg) {
    this.registry = registry;
    this.optimizeCfg = optimizeCfg;
    executorService = Executors.newScheduledThreadPool(1);

    // Create load tester instance
    optimizeLoadTester =
        new OptimizeReportLoadTester(
            optimizeCfg.getBaseUrl(),
            optimizeCfg.getKeycloakUrl(),
            optimizeCfg.getRealm(),
            optimizeCfg.getClientId(),
            optimizeCfg.getUsername(),
            optimizeCfg.getPassword(),
            optimizeCfg.getClientSecret());

    // Initialize metrics
    dashboardResponseTimer =
        MicrometerUtil.buildTimer(OptimizeMetricsDoc.DASHBOARD_RESPONSE_TIME).register(registry);
    maxReportResponseTimer =
        MicrometerUtil.buildTimer(OptimizeMetricsDoc.REPORT_MAX_RESPONSE_TIME).register(registry);
    homepageLoadTimer =
        MicrometerUtil.buildTimer(OptimizeMetricsDoc.HOMEPAGE_LOAD_TIME).register(registry);
    dashboardSuccessCounter =
        Counter.builder(OptimizeMetricsDoc.DASHBOARD_SUCCESS.getName())
            .description(OptimizeMetricsDoc.DASHBOARD_SUCCESS.getDescription())
            .register(registry);
    dashboardErrorCounter =
        Counter.builder(OptimizeMetricsDoc.DASHBOARD_ERROR.getName())
            .description(OptimizeMetricsDoc.DASHBOARD_ERROR.getDescription())
            .register(registry);
    benchmarkDashboardResponseTimer =
        MicrometerUtil.buildTimer(OptimizeMetricsDoc.BENCHMARK_DASHBOARD_RESPONSE_TIME)
            .register(registry);
    benchmarkMaxReportEvaluationTimer =
        MicrometerUtil.buildTimer(OptimizeMetricsDoc.BENCHMARK_REPORT_MAX_EVALUATION_TIME)
            .register(registry);
    benchmarkMaxDetailedEvaluationTimer =
        MicrometerUtil.buildTimer(OptimizeMetricsDoc.BENCHMARK_DETAILED_MAX_EVALUATION_TIME)
            .register(registry);
    benchmarkTotalLoadTimer =
        MicrometerUtil.buildTimer(OptimizeMetricsDoc.BENCHMARK_TOTAL_LOAD_TIME).register(registry);
    benchmarkDashboardSuccessCounter =
        Counter.builder(OptimizeMetricsDoc.BENCHMARK_DASHBOARD_SUCCESS.getName())
            .description(OptimizeMetricsDoc.BENCHMARK_DASHBOARD_SUCCESS.getDescription())
            .register(registry);
    benchmarkDashboardErrorCounter =
        Counter.builder(OptimizeMetricsDoc.BENCHMARK_DASHBOARD_ERROR.getName())
            .description(OptimizeMetricsDoc.BENCHMARK_DASHBOARD_ERROR.getDescription())
            .register(registry);
  }

  /** Authenticates with Keycloak and starts periodic Optimize evaluations. */
  public void start() {
    authenticateWithRetry();

    final int intervalSeconds = optimizeCfg.getEvaluationIntervalSeconds();
    LOG.info(
        "Scheduling Optimize dashboard and report evaluations every {} seconds", intervalSeconds);

    final BooleanSupplier shouldContinue = createContinuationCondition();

    scheduledTask =
        executorService.scheduleAtFixedRate(
            () -> {
              if (!shouldContinue.getAsBoolean()) {
                return;
              }

              try {
                evaluateDashboardAndReports();
                evaluateInstantBenchmark();
              } catch (final Exception e) {
                THROTTLED_LOGGER.error("Error during Optimize evaluation cycle", e);
              }
            },
            10,
            intervalSeconds,
            TimeUnit.SECONDS);

    LOG.info("Optimize evaluation meter started");
  }

  private void authenticateWithRetry() {
    final int maxAttempts = optimizeCfg.getAuthRetryMaxAttempts();
    final int delaySeconds = optimizeCfg.getAuthRetryDelaySeconds();

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        LOG.info("Authenticating Optimize with Keycloak (attempt {}/{})", attempt, maxAttempts);
        optimizeLoadTester.authenticateWithAuthorizationCodeFlow();
        LOG.info("Optimize successfully authenticated");
        return;
      } catch (final Exception e) {
        if (attempt == maxAttempts) {
          LOG.error("Failed to authenticate Optimize after {} attempts", maxAttempts, e);
          throw new RuntimeException(
              "Optimize authentication failed after " + maxAttempts + " attempts", e);
        }
        THROTTLED_LOGGER.warn(
            "Failed to authenticate Optimize (attempt {}/{}), retrying in {}s",
            attempt,
            maxAttempts,
            delaySeconds,
            e);
        try {
          Thread.sleep(delaySeconds * 1000L);
        } catch (final InterruptedException ex) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(
              "Interrupted while waiting to retry Optimize authentication", ex);
        }
      }
    }
  }

  private BooleanSupplier createContinuationCondition() {
    final int durationLimit = optimizeCfg.getDurationLimit();

    if (durationLimit > 0) {
      final LocalDateTime endTime = LocalDateTime.now().plus(durationLimit, ChronoUnit.SECONDS);
      return () -> LocalDateTime.now().isBefore(endTime);
    } else {
      return () -> true;
    }
  }

  private void evaluateDashboardAndReports() {
    LOG.info("Starting Optimize evaluation cycle");

    try {
      optimizeLoadTester.ensureValidToken();

      final OptimizeReportLoadTester.DashboardWithReportsResult result =
          optimizeLoadTester.evaluateDashboardWithReports();

      final OptimizeReportLoadTester.DashboardEvaluationResult dashboardResult =
          result.getDashboardResult();
      recordDashboardMetrics(
          dashboardResult,
          dashboardResponseTimer,
          dashboardSuccessCounter,
          dashboardErrorCounter,
          "Dashboard");

      final List<OptimizeReportLoadTester.ReportEvaluationResult> reportResults =
          result.getReportResults();
      recordReportMetrics(
          reportResults,
          "optimize.report.response.time",
          "Response time for report evaluation",
          "optimize.report.success",
          "Successful report evaluations",
          "optimize.report.error",
          "Failed report evaluations",
          "Report {} [{}] evaluated successfully in {}ms",
          "Report {} [{}] evaluation failed with status {}");

      maxReportResponseTimer.record(result.getMaxReportTimeMs(), TimeUnit.MILLISECONDS);
      homepageLoadTimer.record(result.getHomepageLoadTimeMs(), TimeUnit.MILLISECONDS);

      LOG.info(
          "Optimize evaluation cycle completed - Dashboard: {}ms, Reports: {}, Max report: {}ms, Homepage load: {}ms, Total: {}ms",
          dashboardResult.getResponseTimeMs(),
          reportResults.size(),
          result.getMaxReportTimeMs(),
          result.getHomepageLoadTimeMs(),
          result.getTotalResponseTimeMs());

    } catch (final Exception e) {
      dashboardErrorCounter.increment();
      THROTTLED_LOGGER.error("Failed to evaluate Optimize dashboard and reports", e);
    }
  }

  private void evaluateInstantBenchmark() {
    LOG.info("Starting Optimize instant benchmark evaluation cycle");

    try {
      optimizeLoadTester.ensureValidToken();

      final OptimizeReportLoadTester.InstantBenchmarkResult result =
          optimizeLoadTester.evaluateInstantBenchmark(optimizeCfg.getProcessDefinitionKey());

      final OptimizeReportLoadTester.DashboardEvaluationResult dashboardResult =
          result.getDashboardResult();
      recordDashboardMetrics(
          dashboardResult,
          benchmarkDashboardResponseTimer,
          benchmarkDashboardSuccessCounter,
          benchmarkDashboardErrorCounter,
          "Benchmark dashboard");

      final List<OptimizeReportLoadTester.ReportEvaluationResult> reportEvalResults =
          result.getReportEvaluationResults();
      recordReportMetrics(
          reportEvalResults,
          "optimize.benchmark.report.evaluation.time",
          "Response time for benchmark report evaluation",
          "optimize.benchmark.report.evaluation.success",
          "Successful benchmark report evaluations",
          "optimize.benchmark.report.evaluation.error",
          "Failed benchmark report evaluations",
          "Benchmark report {} [{}] evaluated successfully in {}ms",
          "Benchmark report {} [{}] evaluation failed with status {}");

      final List<OptimizeReportLoadTester.ReportEvaluationResult> detailedResults =
          result.getDetailedEvaluationResults();
      recordReportMetrics(
          detailedResults,
          "optimize.benchmark.detailed.evaluation.time",
          "Response time for benchmark detailed evaluation",
          "optimize.benchmark.detailed.evaluation.success",
          "Successful benchmark detailed evaluations",
          "optimize.benchmark.detailed.evaluation.error",
          "Failed benchmark detailed evaluations",
          "Benchmark detailed evaluation for report {} [{}] completed in {}ms",
          "Benchmark detailed evaluation for report {} [{}] failed with status {}");

      benchmarkMaxReportEvaluationTimer.record(
          result.getMaxReportEvaluationTimeMs(), TimeUnit.MILLISECONDS);
      benchmarkMaxDetailedEvaluationTimer.record(
          result.getMaxDetailedEvaluationTimeMs(), TimeUnit.MILLISECONDS);
      benchmarkTotalLoadTimer.record(result.getTotalResponseTimeMs(), TimeUnit.MILLISECONDS);

      LOG.info(
          "Optimize instant benchmark cycle completed - Dashboard: {}ms, Report evaluations: {}, Detailed evaluations: {}, Max report eval: {}ms, Max detailed eval: {}ms, Total: {}ms",
          dashboardResult.getResponseTimeMs(),
          reportEvalResults.size(),
          detailedResults.size(),
          result.getMaxReportEvaluationTimeMs(),
          result.getMaxDetailedEvaluationTimeMs(),
          result.getTotalResponseTimeMs());

    } catch (final Exception e) {
      benchmarkDashboardErrorCounter.increment();
      THROTTLED_LOGGER.error("Failed to evaluate Optimize instant benchmark", e);
    }
  }

  private void recordDashboardMetrics(
      final OptimizeReportLoadTester.DashboardEvaluationResult dashboardResult,
      final Timer timer,
      final Counter successCounter,
      final Counter errorCounter,
      final String logPrefix) {
    timer.record(dashboardResult.getResponseTimeMs(), TimeUnit.MILLISECONDS);

    if (dashboardResult.isSuccess()) {
      successCounter.increment();
      LOG.info("{} evaluated successfully in {}ms", logPrefix, dashboardResult.getResponseTimeMs());
    } else {
      errorCounter.increment();
      LOG.error("{} evaluation failed with status {}", logPrefix, dashboardResult.getStatusCode());
    }
  }

  private void recordReportMetrics(
      final List<OptimizeReportLoadTester.ReportEvaluationResult> reportResults,
      final String timerMetricName,
      final String timerDescription,
      final String successCounterName,
      final String successDescription,
      final String errorCounterName,
      final String errorDescription,
      final String successLogTemplate,
      final String errorLogTemplate) {
    for (final OptimizeReportLoadTester.ReportEvaluationResult reportResult : reportResults) {
      final String reportName =
          reportResult.getReportName() != null ? reportResult.getReportName() : "unknown";

      Timer.builder(timerMetricName)
          .description(timerDescription)
          .tag("reportId", reportResult.getReportId())
          .tag("reportName", reportName)
          .register(registry)
          .record(reportResult.getResponseTimeMs(), TimeUnit.MILLISECONDS);

      if (reportResult.isSuccess()) {
        Counter.builder(successCounterName)
            .description(successDescription)
            .tag("reportId", reportResult.getReportId())
            .tag("reportName", reportName)
            .register(registry)
            .increment();
        LOG.info(
            successLogTemplate,
            reportResult.getReportId(),
            reportName,
            reportResult.getResponseTimeMs());
      } else {
        Counter.builder(errorCounterName)
            .description(errorDescription)
            .tag("reportId", reportResult.getReportId())
            .tag("reportName", reportName)
            .register(registry)
            .increment();
        LOG.error(
            errorLogTemplate, reportResult.getReportId(), reportName, reportResult.getStatusCode());
      }
    }
  }

  @Override
  public void close() {
    if (scheduledTask != null) {
      scheduledTask.cancel(true);
    }
    executorService.shutdown();
    try {
      executorService.awaitTermination(60, TimeUnit.SECONDS);
    } catch (final InterruptedException e) {
      LOG.error("Shutdown of Optimize evaluation executor was interrupted", e);
    }
    LOG.info("Optimize evaluation meter stopped");
  }
}
