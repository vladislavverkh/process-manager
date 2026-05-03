package dev.verkhovskiy.processmanager.sample.api;

import io.swagger.v3.oas.annotations.media.Schema;

public record PostingResultRequest(
    @Schema(description = "Признак успешного формирования проводки", example = "true")
        Boolean posted,
    @Schema(description = "Идентификатор проводки во внешней системе", example = "posting-1")
        String postingId,
    @Schema(description = "Код ошибки формирования проводки", example = "POSTING_REJECTED")
        String errorCode,
    @Schema(description = "Описание ошибки формирования проводки") String errorMessage,
    @Schema(description = "Ключ идемпотентности ответа", example = "posting-result-1")
        String idempotencyKey) {}
