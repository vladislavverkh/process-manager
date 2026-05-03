package dev.verkhovskiy.processmanager.runtime;

import dev.verkhovskiy.processmanager.ExternalEvent;
import dev.verkhovskiy.processmanager.StateDefinition;
import dev.verkhovskiy.processmanager.StepResult;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class ProcessTriggerMetadata {

  private ProcessTriggerMetadata() {}

  static Map<String, Object> actionTrigger(StepResult result) {
    return switch (result.baseResult()) {
      case StepResult.Success success ->
          Map.of("kind", "SUCCESS", "code", success.code(), "data", success.data());
      case StepResult.BusinessFailure failure ->
          Map.of("kind", "BUSINESS_FAILURE", "code", failure.code(), "data", failure.data());
      case StepResult.RetryableFailure failure ->
          Map.of(
              "kind",
              "RETRYABLE_FAILURE",
              "code",
              failure.code(),
              "message",
              nullToEmpty(failure.message()));
      case StepResult.FatalFailure failure ->
          Map.of(
              "kind",
              "FATAL_FAILURE",
              "code",
              failure.code(),
              "message",
              nullToEmpty(failure.message()));
      case StepResult.WithVariables withVariables -> actionTrigger(withVariables.delegate());
    };
  }

  static Map<String, Object> actionData(StepResult result) {
    return switch (result.baseResult()) {
      case StepResult.Success success -> success.data();
      case StepResult.BusinessFailure failure -> failure.data();
      case StepResult.RetryableFailure failure -> Map.of();
      case StepResult.FatalFailure failure -> Map.of();
      case StepResult.WithVariables withVariables -> actionData(withVariables.delegate());
    };
  }

  static Map<String, Object> eventTrigger(ExternalEvent event) {
    Map<String, Object> trigger = new LinkedHashMap<>();
    trigger.put("eventType", event.eventType());
    trigger.put("correlationKey", event.correlationKey());
    if (event.idempotencyKey() != null) {
      trigger.put("idempotencyKey", event.idempotencyKey());
    }
    trigger.put("payload", event.payload());
    trigger.put("receivedAt", event.receivedAt().toString());
    return Map.copyOf(trigger);
  }

  static Map<String, Object> processTimeoutTrigger(
      String targetState, Instant deadlineAt, Instant triggeredAt) {
    Map<String, Object> trigger = new LinkedHashMap<>();
    trigger.put("targetState", targetState);
    trigger.put("triggeredAt", triggeredAt.toString());
    if (deadlineAt != null) {
      trigger.put("deadlineAt", deadlineAt.toString());
    }
    return Map.copyOf(trigger);
  }

  static Map<String, Object> stateTimeoutTrigger(
      StateDefinition<?> stateDefinition, Instant deadlineAt, Instant triggeredAt) {
    Map<String, Object> trigger = new LinkedHashMap<>();
    trigger.put("state", stateDefinition.name());
    if (stateDefinition.eventType() != null) {
      trigger.put("eventType", stateDefinition.eventType());
    }
    if (stateDefinition.timeoutTargetState() != null) {
      trigger.put("targetState", stateDefinition.timeoutTargetState());
    }
    if (deadlineAt != null) {
      trigger.put("deadlineAt", deadlineAt.toString());
    }
    trigger.put("triggeredAt", triggeredAt.toString());
    return Map.copyOf(trigger);
  }

  static Map<String, Object> timerTrigger(
      StateDefinition<?> stateDefinition, Instant deadlineAt, Instant triggeredAt) {
    Map<String, Object> trigger = new LinkedHashMap<>();
    trigger.put("state", stateDefinition.name());
    trigger.put("targetState", stateDefinition.timeoutTargetState());
    trigger.put("delay", stateDefinition.stateTimeout().toString());
    trigger.put("delayMillis", stateDefinition.stateTimeout().toMillis());
    if (deadlineAt != null) {
      trigger.put("deadlineAt", deadlineAt.toString());
    }
    trigger.put("triggeredAt", triggeredAt.toString());
    return Map.copyOf(trigger);
  }

  static Map<String, Object> retryTrigger(
      StateDefinition<?> stateDefinition, StepResult result, int nextAttempt, Duration delay) {
    Map<String, Object> retry = new LinkedHashMap<>();
    retry.put("state", stateDefinition.name());
    retry.put("attempt", nextAttempt);
    retry.put("maxAttempts", stateDefinition.retryPolicy().maxAttempts());
    retry.put("delay", delay.toString());
    retry.put("delayMillis", delay.toMillis());
    retry.put("failure", actionTrigger(result));
    return Map.copyOf(retry);
  }

  static Map<String, Object> retryExhaustedTrigger(
      StateDefinition<?> stateDefinition, StepResult result, int attempt) {
    Map<String, Object> retry = new LinkedHashMap<>();
    retry.put("state", stateDefinition.name());
    retry.put("attempt", attempt);
    retry.put("maxAttempts", stateDefinition.retryPolicy().maxAttempts());
    retry.put("targetState", stateDefinition.retryExhaustedTargetState());
    retry.put("failure", actionTrigger(result));
    return Map.copyOf(retry);
  }

  static Map<String, Object> triggerVariable(String triggerType, Map<String, Object> trigger) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("type", triggerType);
    value.putAll(trigger == null ? Map.of() : trigger);
    return Map.copyOf(value);
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
