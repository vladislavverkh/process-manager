package dev.verkhovskiy.processmanager.sample;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record CreateTransactionResponse(
    @Schema(description = "Идентификатор процесса process-manager") UUID instanceId,
    @Schema(description = "Бизнесовый идентификатор транзакции", example = "tx-1")
        String transactionId,
    @Schema(description = "Тип запущенного процесса", example = "sample-transaction-polling")
        String processType) {}
