package dev.verkhovskiy.processmanager;

import java.time.Duration;

/** Политика повторов для ACTION-состояний, завершившихся повторяемой ошибкой. */
public record RetryPolicy(
    int maxAttempts, Duration initialDelay, Duration maxDelay, double multiplier) {

  public RetryPolicy {
    if (maxAttempts < 0) {
      throw new IllegalArgumentException("maxAttempts must be greater than or equal to 0");
    }
    if (initialDelay == null || initialDelay.isNegative() || initialDelay.isZero()) {
      throw new IllegalArgumentException("initialDelay must be positive");
    }
    if (maxDelay == null || maxDelay.compareTo(initialDelay) < 0) {
      throw new IllegalArgumentException("maxDelay must be greater than or equal to initialDelay");
    }
    if (multiplier < 1.0d) {
      throw new IllegalArgumentException("multiplier must be greater than or equal to 1");
    }
  }

  /** Без повторных попыток. */
  public static RetryPolicy none() {
    return new RetryPolicy(0, Duration.ofMillis(1), Duration.ofMillis(1), 1.0d);
  }

  /** Экспоненциальная политика повторов. */
  public static RetryPolicy exponential(int maxAttempts, Duration initialDelay, Duration maxDelay) {
    return new RetryPolicy(maxAttempts, initialDelay, maxDelay, 2.0d);
  }

  /** Вычисляет задержку перед следующей попыткой. */
  public Duration delayForAttempt(int nextAttempt) {
    if (nextAttempt <= 1) {
      return initialDelay;
    }
    double multiplierValue = Math.pow(multiplier, nextAttempt - 1);
    long millis = Math.round(initialDelay.toMillis() * multiplierValue);
    return Duration.ofMillis(Math.min(millis, maxDelay.toMillis()));
  }
}
