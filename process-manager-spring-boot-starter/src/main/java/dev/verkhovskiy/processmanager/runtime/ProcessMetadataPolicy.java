package dev.verkhovskiy.processmanager.runtime;

import static dev.verkhovskiy.processmanager.runtime.ProcessRuntimeVariables.LAST_ACTION_RESULT_VARIABLE;
import static dev.verkhovskiy.processmanager.runtime.ProcessRuntimeVariables.LAST_CANCEL_VARIABLE;
import static dev.verkhovskiy.processmanager.runtime.ProcessRuntimeVariables.LAST_EVENT_VARIABLE;
import static dev.verkhovskiy.processmanager.runtime.ProcessRuntimeVariables.LAST_RETRY_VARIABLE;
import static dev.verkhovskiy.processmanager.runtime.ProcessRuntimeVariables.LAST_TRIGGER_VARIABLE;
import static dev.verkhovskiy.processmanager.runtime.ProcessRuntimeVariables.retryMetadataVariable;
import static dev.verkhovskiy.processmanager.runtime.ProcessTriggerMetadata.summaryTrigger;
import static dev.verkhovskiy.processmanager.runtime.ProcessTriggerMetadata.triggerVariable;

import dev.verkhovskiy.processmanager.ProcessVariables;
import java.util.Map;

/** Controls optional diagnostic metadata persisted by the PostgreSQL runtime. */
public final class ProcessMetadataPolicy {

  public static final ProcessMetadataPolicy DEFAULT =
      new ProcessMetadataPolicy(HistoryTrigger.FULL, true, true, true, true, true, true);

  private final HistoryTrigger historyTrigger;
  private final boolean lastTriggerVariable;
  private final boolean lastActionResultVariable;
  private final boolean lastEventVariable;
  private final boolean lastRetryVariable;
  private final boolean lastCancelVariable;
  private final boolean retryMetadataVariable;

  public ProcessMetadataPolicy(
      HistoryTrigger historyTrigger,
      boolean lastTriggerVariable,
      boolean lastActionResultVariable,
      boolean lastEventVariable,
      boolean lastRetryVariable,
      boolean lastCancelVariable,
      boolean retryMetadataVariable) {
    this.historyTrigger = historyTrigger == null ? HistoryTrigger.FULL : historyTrigger;
    this.lastTriggerVariable = lastTriggerVariable;
    this.lastActionResultVariable = lastActionResultVariable;
    this.lastEventVariable = lastEventVariable;
    this.lastRetryVariable = lastRetryVariable;
    this.lastCancelVariable = lastCancelVariable;
    this.retryMetadataVariable = retryMetadataVariable;
  }

  public HistoryTrigger getHistoryTrigger() {
    return historyTrigger;
  }

  public boolean isLastTriggerVariable() {
    return lastTriggerVariable;
  }

  public boolean isLastActionResultVariable() {
    return lastActionResultVariable;
  }

  public boolean isLastEventVariable() {
    return lastEventVariable;
  }

  public boolean isLastRetryVariable() {
    return lastRetryVariable;
  }

  public boolean isLastCancelVariable() {
    return lastCancelVariable;
  }

  public boolean isRetryMetadataVariable() {
    return retryMetadataVariable;
  }

  ProcessVariables withLastTrigger(
      ProcessVariables variables, String triggerType, Map<String, Object> trigger) {
    if (!lastTriggerVariable) {
      return variables;
    }
    return variables.with(LAST_TRIGGER_VARIABLE, triggerVariable(triggerType, trigger));
  }

  ProcessVariables withLastActionResult(
      ProcessVariables variables, Map<String, Object> actionTrigger) {
    if (!lastActionResultVariable) {
      return variables;
    }
    return variables.with(LAST_ACTION_RESULT_VARIABLE, actionTrigger);
  }

  ProcessVariables withLastEvent(ProcessVariables variables, Map<String, Object> eventTrigger) {
    if (!lastEventVariable) {
      return variables;
    }
    return variables.with(LAST_EVENT_VARIABLE, eventTrigger);
  }

  ProcessVariables withLastRetry(ProcessVariables variables, Map<String, Object> retryMetadata) {
    if (!lastRetryVariable) {
      return variables;
    }
    return variables.with(LAST_RETRY_VARIABLE, retryMetadata);
  }

  ProcessVariables withLastCancel(ProcessVariables variables, Map<String, Object> cancelMetadata) {
    if (!lastCancelVariable) {
      return variables;
    }
    return variables.with(LAST_CANCEL_VARIABLE, cancelMetadata);
  }

  ProcessVariables withRetryMetadata(
      ProcessVariables variables, String state, Map<String, Object> retryMetadata) {
    if (!retryMetadataVariable) {
      return variables;
    }
    return variables.with(retryMetadataVariable(state), retryMetadata);
  }

  Map<String, Object> historyTrigger(String triggerType, Map<String, Object> trigger) {
    return switch (historyTrigger) {
      case FULL -> trigger == null ? Map.of() : trigger;
      case SUMMARY -> summaryTrigger(triggerType, trigger);
      case NONE -> Map.of();
    };
  }

  public enum HistoryTrigger {
    FULL,
    SUMMARY,
    NONE
  }
}
