package dev.verkhovskiy.processmanager.sample;

import java.math.BigDecimal;
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

  public List<Map<String, Object>> createTransactionActions(TransactionPayload payload) {
    String contractNumber = payload.contractNumber();
    String transactionId = payload.transactionId();
    String date = payload.transactionDate().toString();
    return switch (payload.transactionType().toUpperCase(Locale.ROOT)) {
      case "ACCRUAL" ->
          List.of(
              action(transactionId, date, "ACCRUE_PRINCIPAL", contractNumber, "1000.00", "LOAN"),
              action(transactionId, date, "ACCRUE_FEE", contractNumber, "50.00", "FEE"));
      case "PAYMENT" ->
          List.of(
              action(transactionId, date, "REPAY_PRINCIPAL", contractNumber, "1000.00", "CURRENT"),
              action(transactionId, date, "REPAY_FEE", contractNumber, "50.00", "CURRENT"));
      case "REVERSAL" ->
          List.of(
              action(
                  transactionId,
                  date,
                  "REVERSE_TRANSACTION",
                  contractNumber,
                  "1050.00",
                  "REVERSAL"));
      default -> List.of();
    };
  }

  public PostingLayoutResult resolvePostingLayout(
      TransactionPayload payload, List<Map<String, Object>> actions) {
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
    return "posting-command-" + payload.transactionId() + "-" + entries.size();
  }

  private boolean shouldFailTemporarily(String key, int failuresBeforeSuccess) {
    int attempt = transientFailures.merge(key, 1, Integer::sum);
    return attempt <= failuresBeforeSuccess;
  }

  private static Map<String, Object> action(
      String transactionId,
      String actionDate,
      String actionType,
      String contractNumber,
      String amount,
      String accountType) {
    return Map.of(
        "actionId",
        actionType + "-" + transactionId,
        "actionDate",
        actionDate,
        "actionType",
        actionType,
        "contractNumber",
        contractNumber,
        "amount",
        new BigDecimal(amount),
        "accountType",
        accountType,
        "transactionId",
        transactionId);
  }

  private static Map<String, Object> postingEntry(
      TransactionPayload payload, Map<String, Object> action) {
    String actionType = action.get("actionType").toString();
    String accountType = action.get("accountType").toString();
    return Map.of(
        "actionId",
        action.get("actionId"),
        "templateId",
        "template-" + actionType,
        "debitAccount",
        debitAccount(accountType, payload.transactionType()),
        "creditAccount",
        creditAccount(accountType, payload.transactionType()),
        "amount",
        action.get("amount"));
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
}
