package dev.verkhovskiy.processmanager.postgres;

import java.time.Instant;
import java.util.UUID;

/** Низкоуровневое PostgreSQL-представление входящего внешнего события. */
public record StoredProcessEvent(
    UUID eventId,
    String eventType,
    String correlationKey,
    String payloadJson,
    Instant receivedAt,
    Instant consumedAt) {}
