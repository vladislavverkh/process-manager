package dev.verkhovskiy.processmanager.postgres;

import java.time.Instant;
import java.util.UUID;

/** Низкоуровневое PostgreSQL-представление входящего внешнего события. */
public record StoredProcessEvent(
    UUID eventId,
    String eventType,
    String correlationKey,
    String idempotencyKey,
    String payloadJson,
    Instant receivedAt,
    Instant consumedAt) {

  public StoredProcessEvent(
      UUID eventId,
      String eventType,
      String correlationKey,
      String payloadJson,
      Instant receivedAt,
      Instant consumedAt) {
    this(eventId, eventType, correlationKey, null, payloadJson, receivedAt, consumedAt);
  }
}
