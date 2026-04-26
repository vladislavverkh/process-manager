package dev.verkhovskiy.processmanager;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Запись истории перехода процесса для чтения и диагностики. */
public record ProcessHistoryView(
    UUID historyId,
    UUID instanceId,
    String processType,
    String fromState,
    String toState,
    String transitionName,
    String triggerType,
    Map<String, Object> trigger,
    Instant createdAt) {

  public ProcessHistoryView {
    trigger = Map.copyOf(trigger == null ? Map.of() : trigger);
  }
}
