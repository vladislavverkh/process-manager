package dev.verkhovskiy.processmanager.runtime;

import dev.verkhovskiy.processmanager.ProcessCommandReason;
import dev.verkhovskiy.processmanager.ProcessInstanceStatus;
import java.time.Duration;

/** Records process-manager runtime metrics without exposing a concrete metrics backend. */
public interface ProcessManagerMetrics {

  void recordProcessStarted(String processType, int definitionVersion, String outcome);

  void recordProcessTerminal(
      String processType,
      int definitionVersion,
      String state,
      ProcessInstanceStatus status,
      Duration duration);

  void recordCommandResumed(String processType, ProcessCommandReason reason, String outcome);

  void recordResumeDuration(
      String processType, ProcessCommandReason reason, String outcome, Duration duration);

  void recordExecutionSteps(String processType, int steps);

  void recordMaxStepsExceeded(String processType);

  void recordAction(
      String processType,
      int definitionVersion,
      String state,
      String resultKind,
      String resultCode,
      Duration duration);

  void recordTransition(
      String processType,
      int definitionVersion,
      String fromState,
      String toState,
      String transitionName,
      String triggerType);

  void recordStateDuration(String processType, String state, Duration duration);

  void recordOptimisticLockConflict(String processType, String state);

  void recordRetryScheduled(
      String processType, String state, int attempt, String resultCode, Duration delay);

  void recordWaitRegistered(String processType, String state, String eventType);

  void recordTimerScheduled(String processType, String state, Duration delay);

  void recordTimerFired(String processType, String state, Duration lag);

  void recordEventReceived(String eventType, String outcome);

  void recordEventMatchedWaits(String eventType, int waitCount);

  void recordEventConsumed(String eventType, Duration lag);

  void recordDeadlineScan(Duration duration, int foundInstances);

  void recordDeadlineCommand(String processType, ProcessCommandReason reason, Duration lag);

  void recordRetentionCleanup(Duration duration, int deletedInstances, String outcome);

  void recordOperatorOperation(String operation, String processType, String outcome);
}
