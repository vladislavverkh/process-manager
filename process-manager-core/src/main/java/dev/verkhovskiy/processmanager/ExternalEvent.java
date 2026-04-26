package dev.verkhovskiy.processmanager;

import java.time.Instant;
import java.util.Map;

/** Внешнее событие, которое возобновляет ожидающий экземпляр процесса. */
public record ExternalEvent(
    String eventType, String correlationKey, Map<String, Object> payload, Instant receivedAt) {

  public ExternalEvent {
    payload = Map.copyOf(payload == null ? Map.of() : payload);
  }
}
