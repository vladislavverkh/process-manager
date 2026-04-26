package dev.verkhovskiy.processmanager;

import java.time.Duration;

/** Retention policy for terminal process instances. */
public record ProcessRetention(Duration completed, Duration failed, Duration cancelled) {

  public ProcessRetention {
    completed = nonNegative(completed, "completed");
    failed = nonNegative(failed, "failed");
    cancelled = nonNegative(cancelled, "cancelled");
  }

  public static ProcessRetention defaults() {
    return new ProcessRetention(Duration.ofDays(30), Duration.ofDays(180), Duration.ofDays(90));
  }

  public Duration forStatus(ProcessInstanceStatus status) {
    return switch (status) {
      case COMPLETED -> completed;
      case FAILED -> failed;
      case CANCELLED -> cancelled;
      case RUNNING, WAITING -> Duration.ZERO;
    };
  }

  private static Duration nonNegative(Duration value, String name) {
    if (value == null || value.isNegative()) {
      throw new IllegalArgumentException(name + " retention must be greater than or equal to 0");
    }
    return value;
  }
}
