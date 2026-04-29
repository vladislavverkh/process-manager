package dev.verkhovskiy.processmanager.sample;

import java.time.LocalDate;

public record TransactionPayload(
    String transactionId,
    LocalDate transactionDate,
    String contractNumber,
    String transactionType) {}
