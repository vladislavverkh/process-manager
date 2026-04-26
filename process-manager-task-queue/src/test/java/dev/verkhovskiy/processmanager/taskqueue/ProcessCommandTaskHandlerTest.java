package dev.verkhovskiy.processmanager.taskqueue;

import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.verkhovskiy.processmanager.ProcessCommand;
import dev.verkhovskiy.processmanager.ProcessCommandReason;
import dev.verkhovskiy.processmanager.ProcessManager;
import dev.verkhovskiy.taskqueue.domain.QueuedTask;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProcessCommandTaskHandlerTest {

  @Mock private ProcessManager processManager;

  @Test
  void passesFullCommandToProcessManager() throws Exception {
    ProcessCommandTaskHandler handler =
        new ProcessCommandTaskHandler(processManager, new ObjectMapper());
    ProcessCommand command =
        new ProcessCommand(
            UUID.fromString("018f0000-0000-7000-8000-000000000001"),
            ProcessCommandReason.TIMEOUT,
            7);

    handler.handle(
        task(
            "{\"instanceId\":\"018f0000-0000-7000-8000-000000000001\",\"reason\":\"TIMEOUT\",\"expectedVersion\":7}"));

    verify(processManager).resume(command);
  }

  private static QueuedTask task(String payload) {
    Instant now = Instant.parse("2026-04-26T12:00:00Z");
    return new QueuedTask(
        UUID.fromString("018f0000-0000-7000-8000-000000000002"),
        TaskQueueProcessCommandScheduler.TASK_TYPE,
        payload,
        "payment:payment-1",
        0,
        now,
        0,
        now);
  }
}
