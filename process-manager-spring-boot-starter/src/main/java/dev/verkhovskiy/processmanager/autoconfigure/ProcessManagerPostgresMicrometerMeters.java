package dev.verkhovskiy.processmanager.autoconfigure;

import dev.verkhovskiy.processmanager.postgres.PostgresProcessRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.ToLongFunction;

final class ProcessManagerPostgresMicrometerMeters {

  private final PostgresProcessRepository processRepository;

  ProcessManagerPostgresMicrometerMeters(
      MeterRegistry meterRegistry, PostgresProcessRepository processRepository) {
    this.processRepository = processRepository;
    registerGauge(
        meterRegistry,
        "process.manager.instances.active",
        PostgresProcessRepository::countActiveInstances);
    registerGauge(
        meterRegistry, "process.manager.waits.active", PostgresProcessRepository::countActiveWaits);
    registerGauge(
        meterRegistry,
        "process.manager.events.unconsumed",
        PostgresProcessRepository::countUnconsumedEvents);
    registerGauge(
        meterRegistry,
        "process.manager.deadline.overdue",
        PostgresProcessRepository::countOverdueDeadlines);
  }

  private void registerGauge(
      MeterRegistry meterRegistry, String name, ToLongFunction<PostgresProcessRepository> value) {
    Gauge.builder(name, this, meters -> meters.count(value))
        .strongReference(true)
        .register(meterRegistry);
  }

  private double count(ToLongFunction<PostgresProcessRepository> value) {
    try {
      return value.applyAsLong(processRepository);
    } catch (RuntimeException e) {
      return Double.NaN;
    }
  }
}
