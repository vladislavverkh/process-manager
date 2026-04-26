package dev.verkhovskiy.processmanager.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.verkhovskiy.processmanager.ProcessDetailsView;
import dev.verkhovskiy.processmanager.ProcessHistoryView;
import dev.verkhovskiy.processmanager.ProcessInspector;
import dev.verkhovskiy.processmanager.ProcessInstanceQuery;
import dev.verkhovskiy.processmanager.ProcessInstanceStatus;
import dev.verkhovskiy.processmanager.ProcessInstanceView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PostgresProcessInspectorTest {

  private static final UUID INSTANCE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000001");
  private static final UUID WAIT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000002");
  private static final UUID HISTORY_ID = UUID.fromString("018f0000-0000-7000-8000-000000000003");

  @Mock private PostgresProcessRepository processRepository;

  private PostgresProcessInspector inspector;

  @BeforeEach
  void setUp() {
    inspector = new PostgresProcessInspector(processRepository, new ObjectMapper());
  }

  @Test
  void findDetailsMapsInstanceWaitsAndHistory() {
    when(processRepository.findInstance(INSTANCE_ID)).thenReturn(Optional.of(instance()));
    when(processRepository.findWaitsByInstance(INSTANCE_ID)).thenReturn(List.of(storedWait()));
    when(processRepository.findHistory(INSTANCE_ID, ProcessInspector.DEFAULT_HISTORY_LIMIT))
        .thenReturn(List.of(history()));

    ProcessDetailsView details = inspector.findDetails(INSTANCE_ID).orElseThrow();

    assertThat(details.instance().payload()).containsEntry("paymentId", "pay-1");
    assertThat(details.instance().variables().values()).containsEntry("attempt", 2);
    assertThat(details.waits()).hasSize(1);
    assertThat(details.waits().getFirst().eventType()).isEqualTo("payment.result");
    assertThat(details.history()).hasSize(1);
    assertThat(details.history().getFirst().trigger()).containsEntry("kind", "SUCCESS");
  }

  @Test
  void findInstancesDelegatesQueryAndMapsRows() {
    ProcessInstanceQuery query =
        ProcessInstanceQuery.builder()
            .processType("payment")
            .activeOnly()
            .deadlineAtOrBefore(Instant.parse("2026-04-26T12:00:00Z"))
            .limit(10)
            .build();
    when(processRepository.findInstances(query)).thenReturn(List.of(instance()));

    List<ProcessInstanceView> instances = inspector.findInstances(query);

    assertThat(instances).hasSize(1);
    assertThat(instances.getFirst().processType()).isEqualTo("payment");
    assertThat(instances.getFirst().status()).isEqualTo(ProcessInstanceStatus.WAITING);
  }

  @Test
  void findHistoryUsesDefaultLimitWhenLimitIsNotPositive() {
    when(processRepository.findHistory(INSTANCE_ID, ProcessInspector.DEFAULT_HISTORY_LIMIT))
        .thenReturn(List.of(history()));

    List<ProcessHistoryView> history = inspector.findHistory(INSTANCE_ID, 0);

    assertThat(history).hasSize(1);
    verify(processRepository).findHistory(INSTANCE_ID, ProcessInspector.DEFAULT_HISTORY_LIMIT);
  }

  @Test
  void findHistoryRejectsTooLargeLimit() {
    assertThatThrownBy(() -> inspector.findHistory(INSTANCE_ID, ProcessInstanceQuery.MAX_LIMIT + 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("history limit");
  }

  @Test
  void queryBuilderCreatesActiveFilter() {
    ProcessInstanceQuery query = ProcessInstanceQuery.builder().activeOnly().build();

    assertThat(query.statuses())
        .isEqualTo(Set.of(ProcessInstanceStatus.RUNNING, ProcessInstanceStatus.WAITING));
    assertThat(query.limit()).isEqualTo(ProcessInstanceQuery.DEFAULT_LIMIT);
  }

  private static StoredProcessInstance instance() {
    return new StoredProcessInstance(
        INSTANCE_ID,
        "payment",
        1,
        1,
        "payment-1",
        "WAIT_RESULT",
        ProcessInstanceStatus.WAITING,
        "{\"paymentId\":\"pay-1\"}",
        "{\"attempt\":2}",
        Instant.parse("2026-04-26T10:00:00Z"),
        Instant.parse("2026-04-26T10:01:00Z"),
        Instant.parse("2026-04-26T12:00:00Z"),
        Instant.parse("2026-04-26T10:01:00Z"),
        Instant.parse("2026-04-26T10:06:00Z"),
        null,
        null,
        3);
  }

  private static StoredProcessWait storedWait() {
    return new StoredProcessWait(
        WAIT_ID,
        INSTANCE_ID,
        "payment",
        "WAIT_RESULT",
        "payment.result",
        "pay-1",
        Instant.parse("2026-04-26T10:06:00Z"),
        Instant.parse("2026-04-26T10:01:00Z"));
  }

  private static ProcessHistoryRecord history() {
    return new ProcessHistoryRecord(
        HISTORY_ID,
        INSTANCE_ID,
        "payment",
        "SEND",
        "WAIT_RESULT",
        "accepted",
        "ACTION_RESULT",
        "{\"kind\":\"SUCCESS\"}",
        Instant.parse("2026-04-26T10:01:00Z"));
  }
}
