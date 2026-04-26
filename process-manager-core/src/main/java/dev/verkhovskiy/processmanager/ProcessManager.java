package dev.verkhovskiy.processmanager;

import java.util.Map;
import java.util.UUID;

/** Публичный API для старта и возобновления экземпляров процессов. */
public interface ProcessManager {

  UUID start(String processType, String businessKey, Object payload);

  void signal(String eventType, String correlationKey, Map<String, Object> payload);

  default void signal(
      String eventType, String correlationKey, String idempotencyKey, Map<String, Object> payload) {
    signal(eventType, correlationKey, payload);
  }

  void resume(UUID instanceId);

  default void resume(ProcessCommand command) {
    resume(command.instanceId());
  }
}
