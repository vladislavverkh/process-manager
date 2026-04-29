package dev.verkhovskiy.processmanager.sample;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;

public record TransactionAction(
    @Schema(description = "Идентификатор бизнесового действия", example = "ACCRUE_PRINCIPAL-tx-1")
        String actionId,
    @Schema(description = "Дата действия", example = "2026-04-29") LocalDate actionDate,
    @Schema(description = "Тип действия", example = "ACCRUE_PRINCIPAL") String actionType,
    @Schema(description = "Номер договора", example = "CONTRACT-1") String contractNumber,
    @Schema(description = "Сумма действия", example = "1000.00") BigDecimal amount,
    @Schema(description = "Тип счета", example = "LOAN") String accountType,
    @Schema(description = "Ссылка на исходную транзакцию", example = "tx-1")
        String transactionId) {}
