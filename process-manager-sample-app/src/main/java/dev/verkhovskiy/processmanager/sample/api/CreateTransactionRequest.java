package dev.verkhovskiy.processmanager.sample.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

public record CreateTransactionRequest(
    @Schema(description = "Бизнесовый идентификатор транзакции", example = "tx-1")
        String transactionId,
    @Schema(description = "Дата транзакции", example = "2026-04-29") LocalDate transactionDate,
    @Schema(description = "Номер договора во внешней системе", example = "CONTRACT-1")
        String contractNumber,
    @Schema(description = "Тип транзакции", example = "ACCRUAL") String transactionType,
    @Schema(description = "Сценарий завершения проводки: POLLING или EVENT", example = "POLLING")
        String completionMode) {}
