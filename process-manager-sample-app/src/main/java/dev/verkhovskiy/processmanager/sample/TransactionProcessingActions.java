package dev.verkhovskiy.processmanager.sample;

import dev.verkhovskiy.processmanager.ProcessContext;
import dev.verkhovskiy.processmanager.StepResult;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Зависимости являются внедренными инфраструктурными Spring-бинами.")
public class TransactionProcessingActions {

  private final MockExternalSystems externalSystems;
  private final TransactionActionRepository actionRepository;

  public TransactionProcessingActions(
      MockExternalSystems externalSystems, TransactionActionRepository actionRepository) {
    this.externalSystems = externalSystems;
    this.actionRepository = actionRepository;
  }

  public StepResult validateTransactionType(ProcessContext<TransactionPayload> context) {
    TransactionPayload payload = context.payload();
    if (!externalSystems.supportsTransactionType(payload.transactionType())) {
      return StepResult.businessFailure(
          "UNSUPPORTED_TRANSACTION_TYPE", Map.of("transactionType", payload.transactionType()));
    }
    return StepResult.success("TRANSACTION_TYPE_SUPPORTED");
  }

  public StepResult lookupClient(ProcessContext<TransactionPayload> context) {
    MockExternalSystems.LookupResult result = externalSystems.findClient(context.payload());
    if (result.success()) {
      return StepResult.success("CLIENT_FOUND", Map.of("clientId", result.id()));
    }
    if (result.temporaryError()) {
      return StepResult.retryableFailure(result.code(), result.message());
    }
    return StepResult.businessFailure(
        result.code(), Map.of("service", "client", "message", result.message()));
  }

  public StepResult lookupContract(ProcessContext<TransactionPayload> context) {
    MockExternalSystems.LookupResult result = externalSystems.findContract(context.payload());
    if (result.success()) {
      return StepResult.success("CONTRACT_FOUND", Map.of("contractId", result.id()));
    }
    if (result.temporaryError()) {
      return StepResult.retryableFailure(result.code(), result.message());
    }
    return StepResult.businessFailure(
        result.code(), Map.of("service", "contract", "message", result.message()));
  }

  public StepResult buildTransactionActions(ProcessContext<TransactionPayload> context) {
    List<TransactionAction> actions = createTransactionActions(context.payload());
    if (actions.isEmpty()) {
      return StepResult.fatalFailure(
          "NO_TRANSACTION_ACTIONS", "No actions were created for transaction");
    }
    actionRepository.replaceByTransactionId(context.payload().transactionId(), actions);
    return StepResult.success("ACTIONS_CREATED", Map.of("actionCount", actions.size()));
  }

  public StepResult preparePostingLayout(ProcessContext<TransactionPayload> context) {
    List<TransactionAction> actions =
        actionRepository.findByTransactionId(context.payload().transactionId());
    if (actions.isEmpty()) {
      return StepResult.fatalFailure(
          "NO_TRANSACTION_ACTIONS", "No actions were found for transaction");
    }
    MockExternalSystems.PostingLayoutResult result =
        externalSystems.resolvePostingLayout(context.payload(), actions);
    if (result.success()) {
      return StepResult.success(
          "POSTING_LAYOUT_READY",
          Map.of("postingLayoutId", result.layoutId(), "postingEntries", result.entries()));
    }
    if (result.temporaryError()) {
      return StepResult.retryableFailure(result.code(), result.message());
    }
    return StepResult.businessFailure(
        result.code(), Map.of("service", "posting-layout", "message", result.message()));
  }

  public StepResult sendPostingCommand(ProcessContext<TransactionPayload> context) {
    List<Map<String, Object>> entries = postingEntries(context);
    if (entries.isEmpty()) {
      return StepResult.fatalFailure("NO_POSTING_ENTRIES", "Posting entries are missing");
    }
    String commandId = externalSystems.sendPostingCommand(context.payload(), entries);
    return StepResult.success("POSTING_COMMAND_SENT", Map.of("postingCommandId", commandId));
  }

  public StepResult pollPostingResult(ProcessContext<TransactionPayload> context) {
    Object commandId = context.variables().values().get("postingCommandId");
    if (commandId == null || commandId.toString().isBlank()) {
      return StepResult.fatalFailure("NO_POSTING_COMMAND_ID", "Posting command id is missing");
    }
    MockExternalSystems.PostingCommandResult result =
        externalSystems.pollPostingCommand(commandId.toString());
    return switch (result.code()) {
      case "POSTING_COMPLETED" ->
          StepResult.success("POSTING_COMPLETED", Map.of("postingId", result.postingId()));
      case "POSTING_PENDING" -> StepResult.success("POSTING_PENDING");
      case "POSTING_REJECTED" ->
          StepResult.businessFailure("POSTING_REJECTED", Map.of("errorCode", result.errorCode()));
      default -> StepResult.fatalFailure(result.code(), "Unexpected posting command status");
    };
  }

  private static List<TransactionAction> createTransactionActions(TransactionPayload payload) {
    String contractNumber = payload.contractNumber();
    return switch (payload.transactionType().toUpperCase(Locale.ROOT)) {
      case "ACCRUAL" ->
          List.of(
              action(payload, "ACCRUE_PRINCIPAL", contractNumber, "1000.00", "LOAN"),
              action(payload, "ACCRUE_FEE", contractNumber, "50.00", "FEE"));
      case "PAYMENT" ->
          List.of(
              action(payload, "REPAY_PRINCIPAL", contractNumber, "1000.00", "CURRENT"),
              action(payload, "REPAY_FEE", contractNumber, "50.00", "CURRENT"));
      case "REVERSAL" ->
          List.of(action(payload, "REVERSE_TRANSACTION", contractNumber, "1050.00", "REVERSAL"));
      default -> List.of();
    };
  }

  private static TransactionAction action(
      TransactionPayload payload,
      String actionType,
      String contractNumber,
      String amount,
      String accountType) {
    return new TransactionAction(
        actionType + "-" + payload.transactionId(),
        payload.transactionDate(),
        actionType,
        contractNumber,
        new BigDecimal(amount),
        accountType,
        payload.transactionId());
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> postingEntries(
      ProcessContext<TransactionPayload> context) {
    Object value = context.variables().values().get("postingEntries");
    if (value instanceof List<?> list) {
      return (List<Map<String, Object>>) list;
    }
    return List.of();
  }
}
