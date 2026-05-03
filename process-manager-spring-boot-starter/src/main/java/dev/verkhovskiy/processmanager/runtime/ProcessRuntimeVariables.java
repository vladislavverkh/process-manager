package dev.verkhovskiy.processmanager.runtime;

import dev.verkhovskiy.processmanager.ProcessVariables;
import dev.verkhovskiy.processmanager.StateDefinition;

final class ProcessRuntimeVariables {

  static final String LAST_TRIGGER_VARIABLE = "_pm.lastTrigger";
  static final String LAST_ACTION_RESULT_VARIABLE = "_pm.lastActionResult";
  static final String LAST_EVENT_VARIABLE = "_pm.lastEvent";
  static final String LAST_RETRY_VARIABLE = "_pm.lastRetry";
  static final String LAST_CANCEL_VARIABLE = "_pm.lastCancel";

  private static final String RETRY_ATTEMPT_VARIABLE_PREFIX = "_pm.retry.";

  private ProcessRuntimeVariables() {}

  static String retryAttemptVariable(String state) {
    return RETRY_ATTEMPT_VARIABLE_PREFIX + state + ".attempt";
  }

  static String retryMetadataVariable(String state) {
    return RETRY_ATTEMPT_VARIABLE_PREFIX + state;
  }

  static boolean canRetry(ProcessExecutionState<?> state, StateDefinition<?> stateDefinition) {
    return retryAttempt(state, stateDefinition) < stateDefinition.retryPolicy().maxAttempts();
  }

  static int retryAttempt(ProcessExecutionState<?> state, StateDefinition<?> stateDefinition) {
    return state.variables().integer(retryAttemptVariable(stateDefinition.name())).orElse(0);
  }

  static ProcessVariables withoutRetryMetadata(ProcessVariables variables, String state) {
    return variables.without(retryAttemptVariable(state)).without(retryMetadataVariable(state));
  }
}
