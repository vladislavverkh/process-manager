package dev.verkhovskiy.processmanager.sample;

import io.swagger.v3.oas.annotations.media.Schema;

public record RetryTransactionRequest(
    @Schema(description = "Ключ идемпотентности ручного retry", example = "manual-retry-1")
        String idempotencyKey) {}
