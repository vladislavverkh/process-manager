package dev.verkhovskiy.processmanager.sample;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

@Component
public class MockExternalSystems {

  private static final Set<String> SUPPORTED_TRANSACTION_TYPES =
      Set.of("ACCRUAL", "PAYMENT", "REVERSAL");

  private final ConcurrentMap<String, Integer> transientFailures = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, PostingCommand> postingCommands = new ConcurrentHashMap<>();

  public boolean supportsTransactionType(String transactionType) {
    return transactionType != null
        && SUPPORTED_TRANSACTION_TYPES.contains(transactionType.toUpperCase(Locale.ROOT));
  }

  public LookupResult findClient(TransactionPayload payload) {
    String key = payload.contractNumber();
    if (key.startsWith("NO-CLIENT")) {
      return LookupResult.businessError("CLIENT_NOT_FOUND", "Client was not found");
    }
    if (key.startsWith("TMP-CLIENT")) {
      return LookupResult.temporaryError("CLIENT_SERVICE_TEMPORARY_UNAVAILABLE");
    }
    if (key.startsWith("FLAKY-CLIENT") && shouldFailTemporarily("client:" + key, 2)) {
      return LookupResult.temporaryError("CLIENT_SERVICE_TEMPORARY_UNAVAILABLE");
    }
    return LookupResult.success("client-" + key);
  }

  public LookupResult findContract(TransactionPayload payload) {
    String key = payload.contractNumber();
    if (key.startsWith("NO-CONTRACT")) {
      return LookupResult.businessError("CONTRACT_NOT_FOUND", "Contract was not found");
    }
    if (key.startsWith("TMP-CONTRACT")) {
      return LookupResult.temporaryError("CONTRACT_SERVICE_TEMPORARY_UNAVAILABLE");
    }
    if (key.startsWith("FLAKY-CONTRACT") && shouldFailTemporarily("contract:" + key, 2)) {
      return LookupResult.temporaryError("CONTRACT_SERVICE_TEMPORARY_UNAVAILABLE");
    }
    return LookupResult.success("contract-" + key);
  }

  public PostingLayoutResult resolvePostingLayout(
      TransactionPayload payload, List<TransactionAction> actions) {
    String key = payload.contractNumber();
    if (key.startsWith("NO-TEMPLATE")) {
      return PostingLayoutResult.businessError(
          "POSTING_TEMPLATE_NOT_FOUND", "Posting template was not found");
    }
    if (key.startsWith("TMP-ACCOUNTS")) {
      return PostingLayoutResult.temporaryError("ACCOUNT_SERVICE_TEMPORARY_UNAVAILABLE");
    }
    if (key.startsWith("FLAKY-ACCOUNTS") && shouldFailTemporarily("accounts:" + key, 2)) {
      return PostingLayoutResult.temporaryError("ACCOUNT_SERVICE_TEMPORARY_UNAVAILABLE");
    }
    List<Map<String, Object>> entries =
        actions.stream().map(action -> postingEntry(payload, action)).toList();
    return PostingLayoutResult.success("layout-" + payload.transactionType(), entries);
  }

  public String sendPostingCommand(TransactionPayload payload, List<Map<String, Object>> entries) {
    String commandId = "posting-command-" + payload.transactionId() + "-" + entries.size();
    boolean reject = payload.contractNumber().startsWith("POSTING-REJECT");
    postingCommands.putIfAbsent(commandId, new PostingCommand(commandId, reject));
    return commandId;
  }

  public PostingCommandResult pollPostingCommand(String commandId) {
    PostingCommand command = postingCommands.get(commandId);
    if (command == null) {
      return PostingCommandResult.rejected("POSTING_COMMAND_NOT_FOUND");
    }
    int poll = transientFailures.merge("posting:" + commandId, 1, Integer::sum);
    if (poll < 3) {
      return PostingCommandResult.pending();
    }
    if (command.reject()) {
      return PostingCommandResult.rejected("POSTING_REJECTED_BY_ACCOUNTING");
    }
    return PostingCommandResult.completed("posting-" + commandId);
  }

  private boolean shouldFailTemporarily(String key, int failuresBeforeSuccess) {
    int attempt = transientFailures.merge(key, 1, Integer::sum);
    return attempt <= failuresBeforeSuccess;
  }

  private static Map<String, Object> postingEntry(
      TransactionPayload payload, TransactionAction action) {
    String actionType = action.actionType();
    String accountType = action.accountType();
    return Map.of(
        "actionId",
        action.actionId(),
        "templateId",
        "template-" + actionType,
        "debitAccount",
        debitAccount(accountType, payload.transactionType()),
        "creditAccount",
        creditAccount(accountType, payload.transactionType()),
        "amount",
        action.amount());
  }

  private static String debitAccount(String accountType, String transactionType) {
    return "D-" + transactionType + "-" + accountType;
  }

  private static String creditAccount(String accountType, String transactionType) {
    return "C-" + transactionType + "-" + accountType;
  }

  public record LookupResult(String code, String id, String message) {
    static LookupResult success(String id) {
      return new LookupResult("OK", id, "");
    }

    static LookupResult temporaryError(String code) {
      return new LookupResult(code, "", "Temporary external service error");
    }

    static LookupResult businessError(String code, String message) {
      return new LookupResult(code, "", message);
    }

    boolean success() {
      return "OK".equals(code);
    }

    boolean temporaryError() {
      return code.endsWith("TEMPORARY_UNAVAILABLE");
    }
  }

  public record PostingLayoutResult(
      String code, String layoutId, List<Map<String, Object>> entries, String message) {
    public PostingLayoutResult {
      entries = List.copyOf(entries == null ? List.of() : entries);
    }

    static PostingLayoutResult success(String layoutId, List<Map<String, Object>> entries) {
      return new PostingLayoutResult("OK", layoutId, entries, "");
    }

    static PostingLayoutResult temporaryError(String code) {
      return new PostingLayoutResult(code, "", List.of(), "Temporary external service error");
    }

    static PostingLayoutResult businessError(String code, String message) {
      return new PostingLayoutResult(code, "", List.of(), message);
    }

    boolean success() {
      return "OK".equals(code);
    }

    boolean temporaryError() {
      return code.endsWith("TEMPORARY_UNAVAILABLE");
    }
  }

  private record PostingCommand(String commandId, boolean reject) {}

  public record PostingCommandResult(String code, String postingId, String errorCode) {
    static PostingCommandResult pending() {
      return new PostingCommandResult("POSTING_PENDING", "", "");
    }

    static PostingCommandResult completed(String postingId) {
      return new PostingCommandResult("POSTING_COMPLETED", postingId, "");
    }

    static PostingCommandResult rejected(String errorCode) {
      return new PostingCommandResult("POSTING_REJECTED", "", errorCode);
    }
  }
}
