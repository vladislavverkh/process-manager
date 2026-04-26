package dev.verkhovskiy.processmanager;

import java.time.Instant;
import java.util.Map;

/** External event used to resume a waiting process instance. */
public record ExternalEvent(
    String eventType, String correlationKey, Map<String, Object> payload, Instant receivedAt) {

  public ExternalEvent {
    payload = Map.copyOf(payload == null ? Map.of() : payload);
  }
}
