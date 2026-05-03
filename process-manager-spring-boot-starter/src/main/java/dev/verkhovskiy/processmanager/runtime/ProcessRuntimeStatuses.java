package dev.verkhovskiy.processmanager.runtime;

import dev.verkhovskiy.processmanager.ProcessDefinition;
import dev.verkhovskiy.processmanager.ProcessDefinitionException;
import dev.verkhovskiy.processmanager.ProcessInstanceStatus;
import dev.verkhovskiy.processmanager.StateDefinition;
import java.time.Instant;

final class ProcessRuntimeStatuses {

  private ProcessRuntimeStatuses() {}

  static ProcessInstanceStatus statusForTarget(StateDefinition<?> targetState) {
    return switch (targetState.kind()) {
      case ACTION, DECISION -> ProcessInstanceStatus.RUNNING;
      case WAIT, TIMER -> ProcessInstanceStatus.WAITING;
      case TERMINAL -> terminalStatus(targetState);
    };
  }

  static ProcessInstanceStatus terminalStatus(StateDefinition<?> stateDefinition) {
    ProcessInstanceStatus terminalStatus = stateDefinition.terminalStatus();
    if (terminalStatus == null) {
      throw new ProcessDefinitionException(
          "Terminal state must define terminal status: " + stateDefinition.name());
    }
    return terminalStatus;
  }

  static boolean terminalInstanceStatus(ProcessInstanceStatus status) {
    return status == ProcessInstanceStatus.COMPLETED
        || status == ProcessInstanceStatus.FAILED
        || status == ProcessInstanceStatus.CANCELLED;
  }

  static Instant deleteAfter(
      ProcessDefinition<?> definition, ProcessInstanceStatus status, Instant completedAt) {
    return completedAt.plus(definition.retention().forStatus(status));
  }
}
