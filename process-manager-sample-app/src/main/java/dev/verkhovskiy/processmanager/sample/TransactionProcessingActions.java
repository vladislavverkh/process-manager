package dev.verkhovskiy.processmanager.sample;

import dev.verkhovskiy.processmanager.ProcessContext;
import dev.verkhovskiy.processmanager.StepResult;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TransactionProcessingActions {

  private final MockExternalSystems externalSystems;

  public TransactionProcessingActions(MockExternalSystems externalSystems) {
    this.externalSystems = externalSystems;
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
    List<Map<String, Object>> actions = externalSystems.createTransactionActions(context.payload());
    if (actions.isEmpty()) {
      return StepResult.fatalFailure(
          "NO_TRANSACTION_ACTIONS", "No actions were created for transaction");
    }
    return StepResult.success("ACTIONS_CREATED", Map.of("transactionActions", actions));
  }

  public StepResult preparePostingLayout(ProcessContext<TransactionPayload> context) {
    List<Map<String, Object>> actions = transactionActions(context);
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

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> transactionActions(
      ProcessContext<TransactionPayload> context) {
    Object value = context.variables().values().get("transactionActions");
    if (value instanceof List<?> list) {
      return (List<Map<String, Object>>) list;
    }
    return List.of();
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
