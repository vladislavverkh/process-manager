package dev.verkhovskiy.processmanager.runtime;

import dev.verkhovskiy.processmanager.ProcessCommandReason;
import dev.verkhovskiy.processmanager.ProcessInstanceStatus;
import java.time.Duration;

/** No-op metrics recorder used when no metrics backend is configured. */
public final class NoopProcessManagerMetrics implements ProcessManagerMetrics {

  public static final NoopProcessManagerMetrics INSTANCE = new NoopProcessManagerMetrics();

  private NoopProcessManagerMetrics() {}

  @Override
  public void recordProcessStarted(String processType, int definitionVersion, String outcome) {}

  @Override
  public void recordProcessTerminal(
      String processType,
      int definitionVersion,
      String state,
      ProcessInstanceStatus status,
      Duration duration) {}

  @Override
  public void recordCommandResumed(
      String processType, ProcessCommandReason reason, String outcome) {}

  @Override
  public void recordResumeDuration(
      String processType, ProcessCommandReason reason, String outcome, Duration duration) {}

  @Override
  public void recordExecutionSteps(String processType, int steps) {}

  @Override
  public void recordMaxStepsExceeded(String processType) {}

  @Override
  public void recordAction(
      String processType,
      int definitionVersion,
      String state,
      String resultKind,
      String resultCode,
      Duration duration) {}

  @Override
  public void recordTransition(
      String processType,
      int definitionVersion,
      String fromState,
      String toState,
      String transitionName,
      String triggerType) {}

  @Override
  public void recordStateDuration(String processType, String state, Duration duration) {}

  @Override
  public void recordOptimisticLockConflict(String processType, String state) {}

  @Override
  public void recordRetryScheduled(
      String processType, String state, int attempt, String resultCode, Duration delay) {}

  @Override
  public void recordWaitRegistered(String processType, String state, String eventType) {}

  @Override
  public void recordTimerScheduled(String processType, String state, Duration delay) {}

  @Override
  public void recordTimerFired(String processType, String state, Duration lag) {}

  @Override
  public void recordEventReceived(String eventType, String outcome) {}

  @Override
  public void recordEventMatchedWaits(String eventType, int waitCount) {}

  @Override
  public void recordEventConsumed(String eventType, Duration lag) {}

  @Override
  public void recordDeadlineScan(Duration duration, int foundInstances) {}

  @Override
  public void recordDeadlineCommand(
      String processType, ProcessCommandReason reason, Duration lag) {}

  @Override
  public void recordRetentionCleanup(Duration duration, int deletedInstances, String outcome) {}

  @Override
  public void recordOperatorOperation(String operation, String processType, String outcome) {}
}
