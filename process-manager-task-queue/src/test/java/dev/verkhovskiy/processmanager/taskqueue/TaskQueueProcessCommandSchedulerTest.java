package dev.verkhovskiy.processmanager.taskqueue;

import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.verkhovskiy.processmanager.ProcessCommand;
import dev.verkhovskiy.processmanager.ProcessCommandReason;
import dev.verkhovskiy.taskqueue.service.TaskProducer;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaskQueueProcessCommandSchedulerTest {

  @Mock private TaskProducer taskProducer;

  @Test
  void schedulesProcessCommandWithProcessPartitionKey() {
    TaskQueueProcessCommandScheduler scheduler =
        new TaskQueueProcessCommandScheduler(taskProducer, new ObjectMapper());
    ProcessCommand command =
        new ProcessCommand(
            UUID.fromString("018f0000-0000-7000-8000-000000000001"),
            ProcessCommandReason.RESUME,
            3);

    scheduler.schedule(command, "payment:payment-1");

    verify(taskProducer)
        .enqueue(
            TaskQueueProcessCommandScheduler.TASK_TYPE,
            "payment:payment-1",
            "{\"instanceId\":\"018f0000-0000-7000-8000-000000000001\",\"reason\":\"RESUME\",\"expectedVersion\":3}");
  }

  @Test
  void schedulesDelayedProcessCommand() {
    TaskQueueProcessCommandScheduler scheduler =
        new TaskQueueProcessCommandScheduler(taskProducer, new ObjectMapper());
    ProcessCommand command =
        new ProcessCommand(
            UUID.fromString("018f0000-0000-7000-8000-000000000001"), ProcessCommandReason.RETRY, 4);
    Duration delay = Duration.ofSeconds(30);

    scheduler.scheduleDelayed(command, "payment:payment-1", delay);

    verify(taskProducer)
        .enqueueDelayed(
            TaskQueueProcessCommandScheduler.TASK_TYPE,
            "payment:payment-1",
            "{\"instanceId\":\"018f0000-0000-7000-8000-000000000001\",\"reason\":\"RETRY\",\"expectedVersion\":4}",
            delay);
  }
}
