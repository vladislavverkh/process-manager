package dev.verkhovskiy.processmanager.runtime;

import dev.verkhovskiy.processmanager.ProcessInstanceStatus;
import dev.verkhovskiy.processmanager.ProcessVariables;
import java.time.Instant;
import java.util.UUID;

record ProcessExecutionState<P>(
    UUID instanceId,
    String processType,
    int definitionVersion,
    String businessKey,
    String state,
    ProcessInstanceStatus status,
    long version,
    P payload,
    ProcessVariables variables,
    Instant startedAt,
    Instant processDeadlineAt,
    Instant stateEnteredAt,
    Instant stateDeadlineAt,
    boolean parked) {

  ProcessExecutionState(
      UUID instanceId,
      String processType,
      int definitionVersion,
      String businessKey,
      String state,
      ProcessInstanceStatus status,
      long version,
      P payload,
      ProcessVariables variables,
      Instant startedAt,
      Instant processDeadlineAt,
      Instant stateEnteredAt,
      Instant stateDeadlineAt) {
    this(
        instanceId,
        processType,
        definitionVersion,
        businessKey,
        state,
        status,
        version,
        payload,
        variables,
        startedAt,
        processDeadlineAt,
        stateEnteredAt,
        stateDeadlineAt,
        false);
  }

  ProcessExecutionState<P> withState(
      String newState,
      ProcessInstanceStatus newStatus,
      long newVersion,
      Instant newStateEnteredAt,
      Instant newStateDeadlineAt) {
    return new ProcessExecutionState<>(
        instanceId,
        processType,
        definitionVersion,
        businessKey,
        newState,
        newStatus,
        newVersion,
        payload,
        variables,
        startedAt,
        processDeadlineAt,
        newStateEnteredAt,
        newStateDeadlineAt,
        false);
  }

  ProcessExecutionState<P> withVersion(long newVersion) {
    return new ProcessExecutionState<>(
        instanceId,
        processType,
        definitionVersion,
        businessKey,
        state,
        status,
        newVersion,
        payload,
        variables,
        startedAt,
        processDeadlineAt,
        stateEnteredAt,
        stateDeadlineAt,
        false);
  }

  ProcessExecutionState<P> withVariables(ProcessVariables newVariables) {
    return new ProcessExecutionState<>(
        instanceId,
        processType,
        definitionVersion,
        businessKey,
        state,
        status,
        version,
        payload,
        newVariables,
        startedAt,
        processDeadlineAt,
        stateEnteredAt,
        stateDeadlineAt,
        false);
  }

  ProcessExecutionState<P> park() {
    return new ProcessExecutionState<>(
        instanceId,
        processType,
        definitionVersion,
        businessKey,
        state,
        status,
        version,
        payload,
        variables,
        startedAt,
        processDeadlineAt,
        stateEnteredAt,
        stateDeadlineAt,
        true);
  }
}
