/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.zeebe.broker.exporter.stream;

import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.zeebe.protocol.record.ValueType;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
final class ExporterMetricsTest {

  private SimpleMeterRegistry meterRegistry;
  private ExporterMetrics metrics;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    metrics = new ExporterMetrics(meterRegistry);
  }

  @Test
  void shouldRecordZeroWhenLatencyIsNegative() {
    // given
    final long written = 2_000L;
    final long exporting = 1_000L; // exporting < written => negative latency

    // when
    metrics.exportingLatency(ValueType.PROCESS_INSTANCE, written, exporting);

    // then
    final Timer timer = meterRegistry.find(ExporterMetricsDoc.EXPORTING_LATENCY.getName()).timer();
    assertThat(timer).isNotNull();
    assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isZero();
  }

  @Test
  void shouldRecordActualLatencyWhenPositive() {
    // given
    final long written = 1_000L;
    final long exporting = 1_500L;

    // when
    metrics.exportingLatency(ValueType.PROCESS_INSTANCE, written, exporting);

    // then
    final Timer timer = meterRegistry.find(ExporterMetricsDoc.EXPORTING_LATENCY.getName()).timer();
    assertThat(timer).isNotNull();
    assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(500.0);
  }
}
