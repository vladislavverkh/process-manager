package dev.verkhovskiy.processmanager.testkit;

import java.time.Instant;
import java.util.Map;

/** Запись in-memory history, которую сохраняет {@link DeterministicProcessRunner}. */
public record TestProcessHistoryRecord(
    String fromState,
    String toState,
    String transitionName,
    String triggerType,
    Map<String, Object> trigger,
    Instant createdAt) {

  public TestProcessHistoryRecord {
    trigger = Map.copyOf(trigger == null ? Map.of() : trigger);
  }
}
