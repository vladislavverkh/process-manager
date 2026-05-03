package dev.verkhovskiy.processmanager.autoconfigure;

import dev.verkhovskiy.processmanager.ProcessCommandReason;
import dev.verkhovskiy.processmanager.ProcessInstanceStatus;
import dev.verkhovskiy.processmanager.runtime.ProcessManagerMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class MicrometerProcessManagerMetrics implements ProcessManagerMetrics {

  private static final String UNKNOWN = "unknown";
  private static final String NONE = "none";

  private final MeterRegistry registry;

  @Override
  public void recordProcessStarted(String processType, int definitionVersion, String outcome) {
    counter("process.manager.instances.started")
        .tag("process.type", value(processType))
        .tag("definition.version", Integer.toString(definitionVersion))
        .tag("outcome", value(outcome))
        .register(registry)
        .increment();
  }

  @Override
  public void recordProcessTerminal(
      String processType,
      int definitionVersion,
      String state,
      ProcessInstanceStatus status,
      Duration duration) {
    counter("process.manager.instances.terminal")
        .tag("process.type", value(processType))
        .tag("definition.version", Integer.toString(definitionVersion))
        .tag("state", value(state))
        .tag("status", statusValue(status))
        .register(registry)
        .increment();
    timer("process.manager.process.duration")
        .tag("process.type", value(processType))
        .tag("definition.version", Integer.toString(definitionVersion))
        .tag("state", value(state))
        .tag("status", statusValue(status))
        .register(registry)
        .record(duration(duration));
  }

  @Override
  public void recordCommandResumed(
      String processType, ProcessCommandReason reason, String outcome) {
    counter("process.manager.commands.resumed")
        .tag("process.type", value(processType))
        .tag("reason", reasonValue(reason))
        .tag("outcome", value(outcome))
        .register(registry)
        .increment();
  }

  @Override
  public void recordResumeDuration(
      String processType, ProcessCommandReason reason, String outcome, Duration duration) {
    timer("process.manager.resume.duration")
        .tag("process.type", value(processType))
        .tag("reason", reasonValue(reason))
        .tag("outcome", value(outcome))
        .register(registry)
        .record(duration(duration));
  }

  @Override
  public void recordExecutionSteps(String processType, int steps) {
    summary("process.manager.execution.steps", "steps")
        .tag("process.type", value(processType))
        .register(registry)
        .record(Math.max(0, steps));
  }

  @Override
  public void recordMaxStepsExceeded(String processType) {
    counter("process.manager.execution.max.steps.exceeded")
        .tag("process.type", value(processType))
        .register(registry)
        .increment();
  }

  @Override
  public void recordAction(
      String processType,
      int definitionVersion,
      String state,
      String resultKind,
      String resultCode,
      Duration duration) {
    timer("process.manager.action.duration")
        .tag("process.type", value(processType))
        .tag("definition.version", Integer.toString(definitionVersion))
        .tag("state", value(state))
        .tag("result.kind", value(resultKind))
        .tag("result.code", value(resultCode))
        .register(registry)
        .record(duration(duration));
  }

  @Override
  public void recordTransition(
      String processType,
      int definitionVersion,
      String fromState,
      String toState,
      String transitionName,
      String triggerType) {
    counter("process.manager.transitions")
        .tag("process.type", value(processType))
        .tag("definition.version", Integer.toString(definitionVersion))
        .tag("from.state", value(fromState))
        .tag("to.state", value(toState))
        .tag("transition.name", value(transitionName))
        .tag("trigger.type", value(triggerType))
        .register(registry)
        .increment();
  }

  @Override
  public void recordStateDuration(String processType, String state, Duration duration) {
    timer("process.manager.state.duration")
        .tag("process.type", value(processType))
        .tag("state", value(state))
        .register(registry)
        .record(duration(duration));
  }

  @Override
  public void recordOptimisticLockConflict(String processType, String state) {
    counter("process.manager.optimistic.lock.conflicts")
        .tag("process.type", value(processType))
        .tag("state", value(state))
        .register(registry)
        .increment();
  }

  @Override
  public void recordRetryScheduled(
      String processType, String state, int attempt, String resultCode, Duration delay) {
    counter("process.manager.retries.scheduled")
        .tag("process.type", value(processType))
        .tag("state", value(state))
        .tag("attempt", Integer.toString(attempt))
        .tag("result.code", value(resultCode))
        .register(registry)
        .increment();
    summary("process.manager.retries.delay", "milliseconds")
        .tag("process.type", value(processType))
        .tag("state", value(state))
        .tag("result.code", value(resultCode))
        .register(registry)
        .record(duration(delay).toMillis());
  }

  @Override
  public void recordWaitRegistered(String processType, String state, String eventType) {
    counter("process.manager.waits.registered")
        .tag("process.type", value(processType))
        .tag("state", value(state))
        .tag("event.type", value(eventType))
        .register(registry)
        .increment();
  }

  @Override
  public void recordTimerScheduled(String processType, String state, Duration delay) {
    counter("process.manager.timers.scheduled")
        .tag("process.type", value(processType))
        .tag("state", value(state))
        .register(registry)
        .increment();
    summary("process.manager.timers.delay", "milliseconds")
        .tag("process.type", value(processType))
        .tag("state", value(state))
        .register(registry)
        .record(duration(delay).toMillis());
  }

  @Override
  public void recordTimerFired(String processType, String state, Duration lag) {
    counter("process.manager.timers.fired")
        .tag("process.type", value(processType))
        .tag("state", value(state))
        .register(registry)
        .increment();
    summary("process.manager.timer.lag", "milliseconds")
        .tag("process.type", value(processType))
        .tag("state", value(state))
        .register(registry)
        .record(duration(lag).toMillis());
  }

  @Override
  public void recordEventReceived(String eventType, String outcome) {
    counter("process.manager.events.received")
        .tag("event.type", value(eventType))
        .tag("outcome", value(outcome))
        .register(registry)
        .increment();
  }

  @Override
  public void recordEventMatchedWaits(String eventType, int waitCount) {
    summary("process.manager.events.matched.waits", "waits")
        .tag("event.type", value(eventType))
        .register(registry)
        .record(Math.max(0, waitCount));
  }

  @Override
  public void recordEventConsumed(String eventType, Duration lag) {
    counter("process.manager.events.consumed")
        .tag("event.type", value(eventType))
        .register(registry)
        .increment();
    timer("process.manager.event.consumption.lag")
        .tag("event.type", value(eventType))
        .register(registry)
        .record(duration(lag));
  }

  @Override
  public void recordDeadlineScan(Duration duration, int foundInstances) {
    timer("process.manager.deadline.scan.duration").register(registry).record(duration(duration));
    summary("process.manager.deadline.scan.instances", "instances")
        .register(registry)
        .record(Math.max(0, foundInstances));
  }

  @Override
  public void recordDeadlineCommand(String processType, ProcessCommandReason reason, Duration lag) {
    counter("process.manager.deadline.commands.scheduled")
        .tag("process.type", value(processType))
        .tag("reason", reasonValue(reason))
        .register(registry)
        .increment();
    summary("process.manager.deadline.lag", "milliseconds")
        .tag("process.type", value(processType))
        .tag("reason", reasonValue(reason))
        .register(registry)
        .record(duration(lag).toMillis());
  }

  @Override
  public void recordRetentionCleanup(Duration duration, int deletedInstances, String outcome) {
    counter("process.manager.retention.cleanup.runs")
        .tag("outcome", value(outcome))
        .register(registry)
        .increment();
    if (deletedInstances > 0) {
      counter("process.manager.retention.cleanup.deleted")
          .tag("outcome", value(outcome))
          .register(registry)
          .increment(deletedInstances);
    }
    timer("process.manager.retention.cleanup.duration")
        .tag("outcome", value(outcome))
        .register(registry)
        .record(duration(duration));
  }

  @Override
  public void recordOperatorOperation(String operation, String processType, String outcome) {
    counter("process.manager.operator.operations")
        .tag("operation", value(operation))
        .tag("process.type", value(processType))
        .tag("outcome", value(outcome))
        .register(registry)
        .increment();
  }

  private static Counter.Builder counter(String name) {
    return Counter.builder(name);
  }

  private static Timer.Builder timer(String name) {
    return Timer.builder(name);
  }

  private static DistributionSummary.Builder summary(String name, String baseUnit) {
    return DistributionSummary.builder(name).baseUnit(baseUnit);
  }

  private static Duration duration(Duration duration) {
    if (duration == null || duration.isNegative()) {
      return Duration.ZERO;
    }
    return duration;
  }

  private static String reasonValue(ProcessCommandReason reason) {
    return reason == null ? UNKNOWN : reason.name();
  }

  private static String statusValue(ProcessInstanceStatus status) {
    return status == null ? UNKNOWN : status.name();
  }

  private static String value(String value) {
    if (value == null || value.isBlank()) {
      return NONE;
    }
    return value;
  }
}
