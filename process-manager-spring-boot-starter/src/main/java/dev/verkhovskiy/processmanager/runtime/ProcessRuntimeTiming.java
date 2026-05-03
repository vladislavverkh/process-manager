package dev.verkhovskiy.processmanager.runtime;

import dev.verkhovskiy.processmanager.ProcessCommand;
import dev.verkhovskiy.processmanager.ProcessCommandReason;
import dev.verkhovskiy.processmanager.StateDefinition;
import dev.verkhovskiy.processmanager.StateKind;
import java.time.Duration;
import java.time.Instant;

final class ProcessRuntimeTiming {

  private ProcessRuntimeTiming() {}

  static boolean processTimeoutCommand(ProcessCommand command) {
    return command.reason() == ProcessCommandReason.PROCESS_TIMEOUT;
  }

  static boolean stateTimeoutCommand(ProcessCommand command) {
    return command.reason() == ProcessCommandReason.STATE_TIMEOUT
        || command.reason() == ProcessCommandReason.TIMEOUT;
  }

  static boolean deadlineExpired(Instant deadlineAt, Instant now) {
    return deadlineAt != null && !deadlineAt.isAfter(now);
  }

  static Instant deadlineAt(Instant start, Duration timeout) {
    return timeout == null ? null : start.plus(timeout);
  }

  static Instant stateDeadlineAt(StateDefinition<?> stateDefinition, Instant enteredAt) {
    return deadlineAt(enteredAt, timeoutForState(stateDefinition));
  }

  static Duration timeoutForState(StateDefinition<?> stateDefinition) {
    if (stateDefinition.stateTimeout() != null) {
      return stateDefinition.stateTimeout();
    }
    return stateDefinition.kind() == StateKind.WAIT ? stateDefinition.waitTimeout() : null;
  }

  static Duration elapsedSince(long startedAtNanos) {
    return Duration.ofNanos(System.nanoTime() - startedAtNanos);
  }

  static Duration durationBetween(Instant start, Instant end) {
    if (start == null || end == null || end.isBefore(start)) {
      return Duration.ZERO;
    }
    return Duration.between(start, end);
  }
}
