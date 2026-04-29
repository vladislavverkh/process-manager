package dev.verkhovskiy.processmanager.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.verkhovskiy.processmanager.ProcessDetailsView;
import dev.verkhovskiy.processmanager.ProcessHistoryView;
import dev.verkhovskiy.processmanager.ProcessInspector;
import dev.verkhovskiy.processmanager.ProcessInstanceStatus;
import dev.verkhovskiy.processmanager.ProcessInstanceView;
import dev.verkhovskiy.processmanager.ProcessOperator;
import dev.verkhovskiy.processmanager.ProcessVariables;
import dev.verkhovskiy.processmanager.ProcessWaitView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ProcessOperatorControllerTest {

  private static final UUID INSTANCE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000001");

  private final ProcessInspector processInspector = mock(ProcessInspector.class);
  private final ProcessOperator processOperator = mock(ProcessOperator.class);
  private final ProcessOperatorController controller =
      new ProcessOperatorController(processInspector, processOperator);

  @Test
  void findDetailsReturnsDetailsWhenInstanceExists() {
    ProcessDetailsView details =
        new ProcessDetailsView(
            instanceView(), List.<ProcessWaitView>of(), List.<ProcessHistoryView>of());
    when(processInspector.findDetails(INSTANCE_ID)).thenReturn(Optional.of(details));

    ResponseEntity<ProcessDetailsView> response = controller.findDetails(INSTANCE_ID);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo(details);
  }

  @Test
  void findDetailsReturnsNotFoundWhenInstanceDoesNotExist() {
    when(processInspector.findDetails(INSTANCE_ID)).thenReturn(Optional.empty());

    ResponseEntity<ProcessDetailsView> response = controller.findDetails(INSTANCE_ID);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void cancelReturnsOkWhenOperationAccepted() {
    when(processOperator.cancel(INSTANCE_ID, "customer request")).thenReturn(true);

    ResponseEntity<ProcessOperationResponse> response =
        controller.cancel(INSTANCE_ID, new CancelProcessRequest("customer request"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo(new ProcessOperationResponse(INSTANCE_ID, true));
  }

  @Test
  void scheduleResumeReturnsAcceptedWhenCommandScheduled() {
    when(processOperator.scheduleResume(INSTANCE_ID)).thenReturn(true);

    ResponseEntity<ProcessOperationResponse> response = controller.scheduleResume(INSTANCE_ID);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getBody()).isEqualTo(new ProcessOperationResponse(INSTANCE_ID, true));
  }

  @Test
  void scheduleRetryReturnsConflictWhenOperationRejectedForExistingInstance() {
    when(processOperator.scheduleRetry(INSTANCE_ID)).thenReturn(false);
    when(processInspector.findInstance(INSTANCE_ID)).thenReturn(Optional.of(instanceView()));

    ResponseEntity<ProcessOperationResponse> response = controller.scheduleRetry(INSTANCE_ID);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).isEqualTo(new ProcessOperationResponse(INSTANCE_ID, false));
  }

  @Test
  void scheduleRetryReturnsNotFoundWhenInstanceDoesNotExist() {
    when(processOperator.scheduleRetry(INSTANCE_ID)).thenReturn(false);
    when(processInspector.findInstance(INSTANCE_ID)).thenReturn(Optional.empty());

    ResponseEntity<ProcessOperationResponse> response = controller.scheduleRetry(INSTANCE_ID);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).isEqualTo(new ProcessOperationResponse(INSTANCE_ID, false));
  }

  private static ProcessInstanceView instanceView() {
    return new ProcessInstanceView(
        INSTANCE_ID,
        "payment",
        1,
        1,
        "payment-1",
        "WAIT_RESULT",
        ProcessInstanceStatus.WAITING,
        Map.of("paymentId", "payment-1"),
        ProcessVariables.empty(),
        Instant.parse("2026-04-26T09:00:00Z"),
        Instant.parse("2026-04-26T09:01:00Z"),
        null,
        Instant.parse("2026-04-26T09:01:00Z"),
        Instant.parse("2026-04-26T10:00:00Z"),
        null,
        null,
        3);
  }
}
