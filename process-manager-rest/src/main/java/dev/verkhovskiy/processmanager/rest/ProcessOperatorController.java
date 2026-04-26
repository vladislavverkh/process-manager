package dev.verkhovskiy.processmanager.rest;

import dev.verkhovskiy.processmanager.ProcessDetailsView;
import dev.verkhovskiy.processmanager.ProcessInspector;
import dev.verkhovskiy.processmanager.ProcessInstanceQuery;
import dev.verkhovskiy.processmanager.ProcessInstanceStatus;
import dev.verkhovskiy.processmanager.ProcessInstanceView;
import dev.verkhovskiy.processmanager.ProcessOperator;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST API для диагностики и ручных операторских действий над процессами. */
@RestController
@RequestMapping("/process-manager/processes")
public class ProcessOperatorController {

  private final ProcessInspector processInspector;
  private final ProcessOperator processOperator;

  public ProcessOperatorController(
      ProcessInspector processInspector, ProcessOperator processOperator) {
    this.processInspector = processInspector;
    this.processOperator = processOperator;
  }

  @GetMapping("/{instanceId}")
  public ResponseEntity<ProcessDetailsView> findDetails(@PathVariable UUID instanceId) {
    return ResponseEntity.of(processInspector.findDetails(instanceId));
  }

  @GetMapping
  public List<ProcessInstanceView> findInstances(
      @RequestParam(required = false) String processType,
      @RequestParam(required = false) String businessKey,
      @RequestParam(required = false) String state,
      @RequestParam(name = "status", required = false) Set<ProcessInstanceStatus> statuses,
      @RequestParam(required = false) Instant deadlineAtOrBefore,
      @RequestParam(required = false) Integer limit) {
    ProcessInstanceQuery query =
        ProcessInstanceQuery.builder()
            .processType(processType)
            .businessKey(businessKey)
            .state(state)
            .statuses(statuses)
            .deadlineAtOrBefore(deadlineAtOrBefore)
            .limit(limit == null ? ProcessInstanceQuery.DEFAULT_LIMIT : limit)
            .build();
    return processInspector.findInstances(query);
  }

  @PostMapping("/{instanceId}/cancel")
  public ResponseEntity<ProcessOperationResponse> cancel(
      @PathVariable UUID instanceId, @RequestBody(required = false) CancelProcessRequest request) {
    boolean accepted =
        processOperator.cancel(instanceId, request == null ? null : request.reason());
    return operationResponse(instanceId, accepted, HttpStatus.OK);
  }

  @PostMapping("/{instanceId}/resume")
  public ResponseEntity<ProcessOperationResponse> scheduleResume(@PathVariable UUID instanceId) {
    return operationResponse(
        instanceId, processOperator.scheduleResume(instanceId), HttpStatus.ACCEPTED);
  }

  @PostMapping("/{instanceId}/retry")
  public ResponseEntity<ProcessOperationResponse> scheduleRetry(@PathVariable UUID instanceId) {
    return operationResponse(
        instanceId, processOperator.scheduleRetry(instanceId), HttpStatus.ACCEPTED);
  }

  private ResponseEntity<ProcessOperationResponse> operationResponse(
      UUID instanceId, boolean accepted, HttpStatus successStatus) {
    ProcessOperationResponse response = new ProcessOperationResponse(instanceId, accepted);
    if (accepted) {
      return ResponseEntity.status(successStatus).body(response);
    }
    if (processInspector.findInstance(instanceId).isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }
    return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
  }
}
