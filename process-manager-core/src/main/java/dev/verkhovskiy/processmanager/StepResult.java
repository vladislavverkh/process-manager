package dev.verkhovskiy.processmanager;

import java.time.Duration;
import java.util.Map;

/** Result returned by an action state before transition selection. */
public sealed interface StepResult {

  /** Successful action result with a business code and structured data. */
  record Success(String code, Map<String, Object> data) implements StepResult {
    public Success {
      data = Map.copyOf(data == null ? Map.of() : data);
    }
  }

  /** Business failure that should be routed by regular process transitions. */
  record BusinessFailure(String code, Map<String, Object> data) implements StepResult {
    public BusinessFailure {
      data = Map.copyOf(data == null ? Map.of() : data);
    }
  }

  /** Retryable technical failure. */
  record RetryableFailure(String code, String message) implements StepResult {}

  /** Non-retryable technical failure. */
  record FatalFailure(String code, String message) implements StepResult {}

  /** Action asks runtime to wait for an external event. */
  record AwaitEvent(String eventType, String correlationKey, Duration timeout)
      implements StepResult {}

  /** Creates a successful result without additional data. */
  static Success success(String code) {
    return new Success(code, Map.of());
  }

  /** Creates a successful result with additional data. */
  static Success success(String code, Map<String, Object> data) {
    return new Success(code, data);
  }

  /** Creates a business failure result. */
  static BusinessFailure businessFailure(String code, Map<String, Object> data) {
    return new BusinessFailure(code, data);
  }

  /** Creates a retryable technical failure. */
  static RetryableFailure retryableFailure(String code, String message) {
    return new RetryableFailure(code, message);
  }

  /** Creates a fatal technical failure. */
  static FatalFailure fatalFailure(String code, String message) {
    return new FatalFailure(code, message);
  }

  /** Creates an await-event result. */
  static AwaitEvent awaitEvent(String eventType, String correlationKey, Duration timeout) {
    return new AwaitEvent(eventType, correlationKey, timeout);
  }
}
