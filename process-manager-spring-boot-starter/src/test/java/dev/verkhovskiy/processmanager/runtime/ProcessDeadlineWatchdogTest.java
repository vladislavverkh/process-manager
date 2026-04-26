package dev.verkhovskiy.processmanager.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.verkhovskiy.processmanager.ProcessCommand;
import dev.verkhovskiy.processmanager.ProcessCommandReason;
import dev.verkhovskiy.processmanager.ProcessCommandScheduler;
import dev.verkhovskiy.processmanager.ProcessInstanceStatus;
import dev.verkhovskiy.processmanager.postgres.PostgresProcessRepository;
import dev.verkhovskiy.processmanager.postgres.StoredProcessInstance;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProcessDeadlineWatchdogTest {

  private static final UUID PROCESS_TIMEOUT_ID =
      UUID.fromString("018f0000-0000-7000-8000-000000000010");
  private static final UUID STATE_TIMEOUT_ID =
      UUID.fromString("018f0000-0000-7000-8000-000000000011");

  @Mock private PostgresProcessRepository processRepository;
  @Mock private ProcessCommandScheduler commandScheduler;

  @Test
  void schedulesCommandsForExpiredDeadlines() {
    StoredProcessInstance processTimeout =
        instance(
            PROCESS_TIMEOUT_ID,
            "process-timeout",
            7,
            Instant.parse("2026-04-26T10:00:00Z"),
            Instant.parse("2026-04-26T10:05:00Z"));
    StoredProcessInstance stateTimeout =
        instance(STATE_TIMEOUT_ID, "state-timeout", 3, null, Instant.parse("2026-04-26T10:00:00Z"));
    when(processRepository.findExpiredDeadlinesForUpdate(50))
        .thenReturn(List.of(processTimeout, stateTimeout));

    ProcessDeadlineWatchdog watchdog =
        new ProcessDeadlineWatchdog(processRepository, commandScheduler, 100);

    int scheduled = watchdog.runOnce(50);

    assertThat(scheduled).isEqualTo(2);
    verify(commandScheduler)
        .schedule(
            new ProcessCommand(PROCESS_TIMEOUT_ID, ProcessCommandReason.PROCESS_TIMEOUT, 7),
            "payment:process-timeout");
    verify(commandScheduler)
        .schedule(
            new ProcessCommand(STATE_TIMEOUT_ID, ProcessCommandReason.STATE_TIMEOUT, 3),
            "payment:state-timeout");
  }

  private static StoredProcessInstance instance(
      UUID instanceId,
      String businessKey,
      long version,
      Instant processDeadlineAt,
      Instant stateDeadlineAt) {
    return new StoredProcessInstance(
        instanceId,
        "payment",
        1,
        1,
        businessKey,
        "WAIT_RESULT",
        ProcessInstanceStatus.WAITING,
        "{}",
        "{}",
        Instant.parse("2026-04-26T09:00:00Z"),
        Instant.parse("2026-04-26T09:00:00Z"),
        processDeadlineAt,
        Instant.parse("2026-04-26T09:00:00Z"),
        stateDeadlineAt,
        null,
        null,
        version);
  }
}
