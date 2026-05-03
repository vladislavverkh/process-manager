package dev.verkhovskiy.processmanager.rest;

import dev.verkhovskiy.processmanager.ProcessDetailsView;
import dev.verkhovskiy.processmanager.ProcessInspector;
import dev.verkhovskiy.processmanager.ProcessOperator;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST API для диагностики и ручных операторских действий над процессами. */
@RestController
@RequestMapping("/process-manager/processes")
@RequiredArgsConstructor
public class ProcessOperatorController {

  private final ProcessInspector processInspector;
  private final ProcessOperator processOperator;

  @GetMapping("/{instanceId}")
  public ResponseEntity<ProcessDetailsView> findDetails(
      @PathVariable("instanceId") UUID instanceId) {
    return ResponseEntity.of(processInspector.findDetails(instanceId));
  }

  @PostMapping("/{instanceId}/cancel")
  public ResponseEntity<ProcessOperationResponse> cancel(
      @PathVariable("instanceId") UUID instanceId,
      @RequestBody(required = false) CancelProcessRequest request) {
    boolean accepted =
        processOperator.cancel(instanceId, request == null ? null : request.reason());
    return operationResponse(instanceId, accepted, HttpStatus.OK);
  }

  @PostMapping("/{instanceId}/resume")
  public ResponseEntity<ProcessOperationResponse> scheduleResume(
      @PathVariable("instanceId") UUID instanceId) {
    return operationResponse(
        instanceId, processOperator.scheduleResume(instanceId), HttpStatus.ACCEPTED);
  }

  @PostMapping("/{instanceId}/retry")
  public ResponseEntity<ProcessOperationResponse> scheduleRetry(
      @PathVariable("instanceId") UUID instanceId) {
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
