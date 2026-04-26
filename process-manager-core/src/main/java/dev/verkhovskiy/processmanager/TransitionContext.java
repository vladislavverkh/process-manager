package dev.verkhovskiy.processmanager;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Context passed to conditional transitions. */
public record TransitionContext<P>(
    UUID instanceId,
    String processType,
    int definitionVersion,
    String state,
    String businessKey,
    P payload,
    ProcessVariables variables,
    StepResult actionResult,
    ExternalEvent event,
    Instant now) {

  public Optional<StepResult> actionResultOptional() {
    return Optional.ofNullable(actionResult);
  }

  public Optional<ExternalEvent> eventOptional() {
    return Optional.ofNullable(event);
  }

  public boolean resultCodeEquals(String code) {
    if (actionResult instanceof StepResult.Success success) {
      return success.code().equals(code);
    }
    if (actionResult instanceof StepResult.BusinessFailure failure) {
      return failure.code().equals(code);
    }
    if (actionResult instanceof StepResult.RetryableFailure failure) {
      return failure.code().equals(code);
    }
    if (actionResult instanceof StepResult.FatalFailure failure) {
      return failure.code().equals(code);
    }
    if (actionResult instanceof StepResult.AwaitEvent awaitEvent) {
      return awaitEvent.eventType().equals(code);
    }
    return false;
  }

  public boolean eventFieldEquals(String field, Object expected) {
    return event != null && expected.equals(event.payload().get(field));
  }
}
