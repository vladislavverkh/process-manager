package dev.verkhovskiy.processmanager;

import java.util.Map;
import java.util.UUID;

/** Public API for starting and resuming process instances. */
public interface ProcessManager {

  UUID start(String processType, String businessKey, Object payload);

  void signal(String eventType, String correlationKey, Map<String, Object> payload);

  void resume(UUID instanceId);
}
