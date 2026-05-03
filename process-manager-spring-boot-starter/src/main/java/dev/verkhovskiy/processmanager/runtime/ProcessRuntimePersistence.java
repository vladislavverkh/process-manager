package dev.verkhovskiy.processmanager.runtime;

import static dev.verkhovskiy.processmanager.runtime.ProcessRuntimeStatuses.deleteAfter;
import static dev.verkhovskiy.processmanager.runtime.ProcessRuntimeStatuses.statusForTarget;
import static dev.verkhovskiy.processmanager.runtime.ProcessRuntimeStatuses.terminalStatus;
import static dev.verkhovskiy.processmanager.runtime.ProcessRuntimeTiming.durationBetween;
import static dev.verkhovskiy.processmanager.runtime.ProcessRuntimeTiming.stateDeadlineAt;
import static dev.verkhovskiy.processmanager.runtime.ProcessRuntimeVariables.LAST_RETRY_VARIABLE;
import static dev.verkhovskiy.processmanager.runtime.ProcessRuntimeVariables.LAST_TRIGGER_VARIABLE;
import static dev.verkhovskiy.processmanager.runtime.ProcessRuntimeVariables.retryAttempt;
import static dev.verkhovskiy.processmanager.runtime.ProcessRuntimeVariables.retryAttemptVariable;
import static dev.verkhovskiy.processmanager.runtime.ProcessRuntimeVariables.retryMetadataVariable;
import static dev.verkhovskiy.processmanager.runtime.ProcessTriggerMetadata.actionResultCode;
import static dev.verkhovskiy.processmanager.runtime.ProcessTriggerMetadata.retryExhaustedTrigger;
import static dev.verkhovskiy.processmanager.runtime.ProcessTriggerMetadata.retryTrigger;
import static dev.verkhovskiy.processmanager.runtime.ProcessTriggerMetadata.timerTrigger;
import static dev.verkhovskiy.processmanager.runtime.ProcessTriggerMetadata.triggerVariable;

import dev.verkhovskiy.processmanager.ProcessCommand;
import dev.verkhovskiy.processmanager.ProcessCommandReason;
import dev.verkhovskiy.processmanager.ProcessCommandScheduler;
import dev.verkhovskiy.processmanager.ProcessContext;
import dev.verkhovskiy.processmanager.ProcessDefinition;
import dev.verkhovskiy.processmanager.ProcessInstanceStatus;
import dev.verkhovskiy.processmanager.ProcessVariables;
import dev.verkhovskiy.processmanager.StateDefinition;
import dev.verkhovskiy.processmanager.StateKind;
import dev.verkhovskiy.processmanager.StepResult;
import dev.verkhovskiy.processmanager.TransitionDefinition;
import dev.verkhovskiy.processmanager.postgres.PostgresProcessRepository;
import dev.verkhovskiy.processmanager.postgres.ProcessHistoryRecord;
import dev.verkhovskiy.processmanager.postgres.StoredProcessWait;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

final class ProcessRuntimePersistence {

  private final PostgresProcessRepository processRepository;
  private final ProcessCommandScheduler commandScheduler;
  private final ProcessRuntimeJson runtimeJson;
  private final ProcessManagerMetrics metrics;

  ProcessRuntimePersistence(
      PostgresProcessRepository processRepository,
      ProcessCommandScheduler commandScheduler,
      ProcessRuntimeJson runtimeJson,
      ProcessManagerMetrics metrics) {
    this.processRepository = processRepository;
    this.commandScheduler = commandScheduler;
    this.runtimeJson = runtimeJson;
    this.metrics = metrics;
  }

  <P> ProcessExecutionState<P> enterTerminal(
      ProcessDefinition<P> definition,
      ProcessExecutionState<P> state,
      StateDefinition<P> stateDefinition) {
    if (state.status() == stateDefinition.terminalStatus()) {
      return state.park();
    }
    Instant now = Instant.now();
    ProcessInstanceStatus terminalStatus = terminalStatus(stateDefinition);
    ProcessVariables variables =
        state.variables().with(LAST_TRIGGER_VARIABLE, triggerVariable("START", Map.of()));
    int updated =
        processRepository.updateExecutionState(
            state.instanceId(),
            state.version(),
            stateDefinition.name(),
            terminalStatus,
            runtimeJson.toJson(variables.values()),
            now,
            null,
            now,
            deleteAfter(definition, terminalStatus, now));
    if (updated == 0) {
      recordOptimisticLockConflict(state);
      return state.park();
    }
    insertHistory(state, null, stateDefinition.name(), "START", "START", Map.of(), now);
    metrics.recordTransition(
        definition.processType(),
        definition.version(),
        null,
        stateDefinition.name(),
        "START",
        "START");
    metrics.recordProcessTerminal(
        definition.processType(),
        definition.version(),
        stateDefinition.name(),
        terminalStatus,
        durationBetween(state.startedAt(), now));
    return state
        .withVariables(variables)
        .withState(stateDefinition.name(), terminalStatus, state.version() + 1, now, null)
        .park();
  }

  <P> ProcessExecutionState<P> applyTransition(
      ProcessDefinition<P> definition,
      ProcessExecutionState<P> state,
      StateDefinition<P> fromState,
      TransitionDefinition<P> transition,
      String triggerType,
      Map<String, Object> trigger,
      Instant now) {
    StateDefinition<P> targetState = definition.state(transition.targetState());
    ProcessInstanceStatus status = statusForTarget(targetState);
    Instant stateEnteredAt = now;
    Instant stateDeadlineAt = stateDeadlineAt(targetState, stateEnteredAt);
    Instant completedAt = targetState.terminal() ? now : null;
    Instant deleteAfter = targetState.terminal() ? deleteAfter(definition, status, now) : null;
    int updated =
        processRepository.updateExecutionState(
            state.instanceId(),
            state.version(),
            targetState.name(),
            status,
            runtimeJson.toJson(state.variables().values()),
            stateEnteredAt,
            stateDeadlineAt,
            completedAt,
            deleteAfter);
    if (updated == 0) {
      recordOptimisticLockConflict(state);
      return state.park();
    }
    if (fromState.kind() == StateKind.WAIT) {
      processRepository.deleteWaits(state.instanceId());
    }
    processRepository.insertHistory(
        new ProcessHistoryRecord(
            UUID.randomUUID(),
            state.instanceId(),
            state.processType(),
            fromState.name(),
            targetState.name(),
            transition.name(),
            triggerType,
            runtimeJson.toJson(trigger),
            now));
    metrics.recordTransition(
        definition.processType(),
        definition.version(),
        fromState.name(),
        targetState.name(),
        transition.name(),
        triggerType);
    metrics.recordStateDuration(
        definition.processType(), fromState.name(), durationBetween(state.stateEnteredAt(), now));
    if (targetState.terminal()) {
      metrics.recordProcessTerminal(
          definition.processType(),
          definition.version(),
          targetState.name(),
          status,
          durationBetween(state.startedAt(), now));
    }

    ProcessExecutionState<P> updatedState =
        state.withState(
            targetState.name(), status, state.version() + 1, stateEnteredAt, stateDeadlineAt);
    if (targetState.kind() == StateKind.WAIT) {
      return registerWait(updatedState, targetState, now).park();
    }
    if (targetState.kind() == StateKind.TIMER) {
      return scheduleTimer(definition, updatedState, targetState, now).park();
    }
    if (targetState.kind() == StateKind.TERMINAL) {
      return updatedState.park();
    }
    return updatedState;
  }

  <P> ProcessExecutionState<P> parkInWait(
      ProcessExecutionState<P> state,
      StateDefinition<P> stateDefinition,
      String triggerType,
      Map<String, Object> trigger,
      Instant now) {
    ProcessVariables variables =
        state.variables().with(LAST_TRIGGER_VARIABLE, triggerVariable(triggerType, trigger));
    ProcessExecutionState<P> stateWithTrigger = state.withVariables(variables);
    int updated =
        processRepository.updateExecutionState(
            stateWithTrigger.instanceId(),
            stateWithTrigger.version(),
            stateDefinition.name(),
            ProcessInstanceStatus.WAITING,
            runtimeJson.toJson(stateWithTrigger.variables().values()),
            now,
            stateDeadlineAt(stateDefinition, now),
            null,
            null);
    if (updated == 0) {
      recordOptimisticLockConflict(state);
      return state.park();
    }
    insertHistory(
        stateWithTrigger,
        null,
        stateDefinition.name(),
        stateDefinition.name(),
        triggerType,
        trigger,
        now);
    metrics.recordTransition(
        state.processType(),
        state.definitionVersion(),
        null,
        stateDefinition.name(),
        stateDefinition.name(),
        triggerType);
    return registerWait(
            stateWithTrigger.withState(
                stateDefinition.name(),
                ProcessInstanceStatus.WAITING,
                state.version() + 1,
                now,
                stateDeadlineAt(stateDefinition, now)),
            stateDefinition,
            now)
        .park();
  }

  <P> ProcessExecutionState<P> parkInTimer(
      ProcessDefinition<P> definition,
      ProcessExecutionState<P> state,
      StateDefinition<P> stateDefinition,
      String triggerType,
      Map<String, Object> trigger,
      Instant now) {
    ProcessVariables variables =
        state.variables().with(LAST_TRIGGER_VARIABLE, triggerVariable(triggerType, trigger));
    ProcessExecutionState<P> stateWithTrigger = state.withVariables(variables);
    Instant stateDeadlineAt = stateDeadlineAt(stateDefinition, now);
    int updated =
        processRepository.updateExecutionState(
            stateWithTrigger.instanceId(),
            stateWithTrigger.version(),
            stateDefinition.name(),
            ProcessInstanceStatus.WAITING,
            runtimeJson.toJson(stateWithTrigger.variables().values()),
            now,
            stateDeadlineAt,
            null,
            null);
    if (updated == 0) {
      recordOptimisticLockConflict(state);
      return state.park();
    }
    insertHistory(
        stateWithTrigger,
        null,
        stateDefinition.name(),
        stateDefinition.name(),
        triggerType,
        trigger,
        now);
    metrics.recordTransition(
        state.processType(),
        state.definitionVersion(),
        null,
        stateDefinition.name(),
        stateDefinition.name(),
        triggerType);
    return scheduleTimer(
            definition,
            stateWithTrigger.withState(
                stateDefinition.name(),
                ProcessInstanceStatus.WAITING,
                state.version() + 1,
                now,
                stateDeadlineAt),
            stateDefinition,
            now)
        .park();
  }

  <P> ProcessExecutionState<P> scheduleRetry(
      ProcessDefinition<P> definition,
      ProcessExecutionState<P> state,
      StateDefinition<P> stateDefinition,
      StepResult result,
      Instant now) {
    int nextAttempt = retryAttempt(state, stateDefinition) + 1;
    Duration delay = stateDefinition.retryPolicy().delayForAttempt(nextAttempt);
    Map<String, Object> retryMetadata = retryTrigger(stateDefinition, result, nextAttempt, delay);
    ProcessVariables variables =
        state
            .variables()
            .with(retryAttemptVariable(stateDefinition.name()), nextAttempt)
            .with(retryMetadataVariable(stateDefinition.name()), retryMetadata)
            .with(LAST_RETRY_VARIABLE, retryMetadata)
            .with(LAST_TRIGGER_VARIABLE, triggerVariable("RETRY", retryMetadata));
    int updated =
        processRepository.updateExecutionState(
            state.instanceId(),
            state.version(),
            state.state(),
            ProcessInstanceStatus.RUNNING,
            runtimeJson.toJson(variables.values()),
            state.stateEnteredAt(),
            state.stateDeadlineAt(),
            null,
            null);
    if (updated == 0) {
      recordOptimisticLockConflict(state);
      return state.park();
    }
    long nextVersion = state.version() + 1;
    commandScheduler.scheduleDelayed(
        new ProcessCommand(state.instanceId(), ProcessCommandReason.RETRY, nextVersion),
        partitionKey(definition.processType(), state.businessKey()),
        delay);
    processRepository.insertHistory(
        new ProcessHistoryRecord(
            UUID.randomUUID(),
            state.instanceId(),
            state.processType(),
            state.state(),
            state.state(),
            "retry",
            "RETRY",
            runtimeJson.toJson(retryMetadata),
            now));
    metrics.recordRetryScheduled(
        definition.processType(),
        stateDefinition.name(),
        nextAttempt,
        actionResultCode(result),
        delay);
    return state.withVariables(variables).withVersion(nextVersion).park();
  }

  <P> ProcessExecutionState<P> applyRetryExhaustedTransition(
      ProcessDefinition<P> definition,
      ProcessExecutionState<P> state,
      StateDefinition<P> stateDefinition,
      StepResult result,
      Instant now) {
    Map<String, Object> retryMetadata =
        retryExhaustedTrigger(stateDefinition, result, retryAttempt(state, stateDefinition));
    ProcessVariables variables =
        state
            .variables()
            .with(LAST_RETRY_VARIABLE, retryMetadata)
            .with(LAST_TRIGGER_VARIABLE, triggerVariable("RETRY_EXHAUSTED", retryMetadata));
    return applyTransition(
        definition,
        state.withVariables(variables),
        stateDefinition,
        syntheticTransition("retry-exhausted", stateDefinition.retryExhaustedTargetState()),
        "RETRY_EXHAUSTED",
        retryMetadata,
        now);
  }

  private <P> ProcessExecutionState<P> scheduleTimer(
      ProcessDefinition<P> definition,
      ProcessExecutionState<P> state,
      StateDefinition<P> stateDefinition,
      Instant now) {
    Duration delay = stateDefinition.stateTimeout();
    commandScheduler.scheduleDelayed(
        new ProcessCommand(state.instanceId(), ProcessCommandReason.RESUME, state.version()),
        partitionKey(definition.processType(), state.businessKey()),
        delay);
    processRepository.insertHistory(
        new ProcessHistoryRecord(
            UUID.randomUUID(),
            state.instanceId(),
            state.processType(),
            state.state(),
            state.state(),
            "timer-scheduled",
            "TIMER_SCHEDULED",
            runtimeJson.toJson(timerTrigger(stateDefinition, state.stateDeadlineAt(), now)),
            now));
    metrics.recordTimerScheduled(definition.processType(), stateDefinition.name(), delay);
    return state;
  }

  private <P> ProcessExecutionState<P> registerWait(
      ProcessExecutionState<P> state, StateDefinition<P> stateDefinition, Instant now) {
    String correlationKey =
        stateDefinition.correlationKeyResolver().resolve(processContext(state, now));
    Instant expiresAt =
        stateDefinition.waitTimeout() == null ? null : now.plus(stateDefinition.waitTimeout());
    processRepository.upsertWait(
        new StoredProcessWait(
            UUID.randomUUID(),
            state.instanceId(),
            state.processType(),
            stateDefinition.name(),
            stateDefinition.eventType(),
            correlationKey,
            expiresAt,
            now));
    metrics.recordWaitRegistered(
        state.processType(), stateDefinition.name(), stateDefinition.eventType());
    return state;
  }

  private <P> ProcessContext<P> processContext(ProcessExecutionState<P> state, Instant now) {
    return new ProcessContext<>(
        state.instanceId(),
        state.processType(),
        state.definitionVersion(),
        state.state(),
        state.businessKey(),
        state.payload(),
        state.variables(),
        now);
  }

  private <P> void insertHistory(
      ProcessExecutionState<P> state,
      String fromState,
      String toState,
      String transitionName,
      String triggerType,
      Map<String, Object> trigger,
      Instant now) {
    processRepository.insertHistory(
        new ProcessHistoryRecord(
            UUID.randomUUID(),
            state.instanceId(),
            state.processType(),
            fromState,
            toState,
            transitionName,
            triggerType,
            runtimeJson.toJson(trigger),
            now));
  }

  private void recordOptimisticLockConflict(ProcessExecutionState<?> state) {
    metrics.recordOptimisticLockConflict(state.processType(), state.state());
  }

  private static <P> TransitionDefinition<P> syntheticTransition(String name, String targetState) {
    return new TransitionDefinition<>(name, targetState, Integer.MIN_VALUE, context -> true);
  }

  private static String partitionKey(String processType, String key) {
    return processType + ":" + key;
  }
}
