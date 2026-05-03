package dev.verkhovskiy.processmanager.sample;

import dev.verkhovskiy.processmanager.ProcessDetailsView;
import dev.verkhovskiy.processmanager.ProcessInspector;
import dev.verkhovskiy.processmanager.ProcessInstanceQuery;
import dev.verkhovskiy.processmanager.ProcessInstanceView;
import dev.verkhovskiy.processmanager.ProcessManager;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sample/transactions")
@Tag(name = "Sample transactions", description = "Пример процесса обработки транзакции")
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Зависимости являются внедренными инфраструктурными Spring-бинами.")
@RequiredArgsConstructor
public class SampleTransactionController {

  private final ProcessManager processManager;
  private final ProcessInspector processInspector;
  private final TransactionActionRepository actionRepository;

  @PostMapping
  @Operation(summary = "Запустить обработку транзакции")
  public CreateTransactionResponse create(@RequestBody CreateTransactionRequest request) {
    TransactionPayload payload =
        new TransactionPayload(
            request.transactionId(),
            request.transactionDate(),
            request.contractNumber(),
            request.transactionType());
    String processType = processType(request);
    UUID instanceId = processManager.start(processType, request.transactionId(), payload);
    return new CreateTransactionResponse(instanceId, request.transactionId(), processType);
  }

  @PostMapping("/{transactionId}/posting-result")
  @Operation(summary = "Передать результат формирования проводки для event-based сценария")
  public void postingResult(
      @PathVariable String transactionId, @RequestBody PostingResultRequest request) {
    processManager.signal(
        TransactionProcessConfiguration.EVENT_POSTING_RESULT,
        transactionId,
        request.idempotencyKey(),
        Map.of(
            "posted",
            Boolean.TRUE.equals(request.posted()),
            "postingId",
            nullToEmpty(request.postingId()),
            "errorCode",
            nullToEmpty(request.errorCode()),
            "errorMessage",
            nullToEmpty(request.errorMessage())));
  }

  @PostMapping("/{transactionId}/retry")
  @Operation(summary = "Возобновить припаркованный процесс после временной ошибки")
  public void retryParked(
      @PathVariable String transactionId,
      @RequestBody(required = false) RetryTransactionRequest request) {
    processManager.signal(
        TransactionProcessConfiguration.EVENT_RETRY_PARKED,
        transactionId,
        request == null ? null : request.idempotencyKey(),
        Map.of("retry", true));
  }

  @GetMapping
  @Operation(summary = "Список процессов обработки транзакций")
  public List<ProcessInstanceView> list() {
    return processInspector.findInstances(ProcessInstanceQuery.builder().limit(100).build());
  }

  @GetMapping("/{transactionId}/actions")
  @Operation(summary = "Список бизнесовых действий по транзакции")
  public List<TransactionAction> actions(@PathVariable String transactionId) {
    return actionRepository.findByTransactionId(transactionId);
  }

  @GetMapping("/{instanceId}")
  @Operation(summary = "Детали процесса обработки транзакции")
  public ResponseEntity<ProcessDetailsView> details(@PathVariable UUID instanceId) {
    return ResponseEntity.of(processInspector.findDetails(instanceId));
  }

  private static String processType(CreateTransactionRequest request) {
    if ("EVENT".equalsIgnoreCase(request.completionMode())
        || "KAFKA".equalsIgnoreCase(request.completionMode())) {
      return TransactionProcessConfiguration.EVENT_PROCESS_TYPE;
    }
    return TransactionProcessConfiguration.POLLING_PROCESS_TYPE;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
