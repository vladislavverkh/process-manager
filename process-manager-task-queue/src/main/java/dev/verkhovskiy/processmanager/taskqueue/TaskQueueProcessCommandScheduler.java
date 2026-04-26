package dev.verkhovskiy.processmanager.taskqueue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.verkhovskiy.processmanager.ProcessCommand;
import dev.verkhovskiy.processmanager.ProcessCommandScheduler;
import dev.verkhovskiy.taskqueue.service.TaskProducer;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;

/** Schedules process resume commands through task-queue-postgres. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "TaskProducer and ObjectMapper are injected infrastructure beans.")
public class TaskQueueProcessCommandScheduler implements ProcessCommandScheduler {

  public static final String TASK_TYPE = "process-manager.command";

  private final TaskProducer taskProducer;
  private final ObjectMapper objectMapper;

  public TaskQueueProcessCommandScheduler(TaskProducer taskProducer, ObjectMapper objectMapper) {
    this.taskProducer = taskProducer;
    this.objectMapper = objectMapper;
  }

  @Override
  public void schedule(ProcessCommand command, String partitionKey) {
    taskProducer.enqueue(TASK_TYPE, partitionKey, payload(command));
  }

  @Override
  public void scheduleDelayed(ProcessCommand command, String partitionKey, Duration delay) {
    taskProducer.enqueueDelayed(TASK_TYPE, partitionKey, payload(command), delay);
  }

  private String payload(ProcessCommand command) {
    try {
      return objectMapper.writeValueAsString(command);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Cannot serialize process command", e);
    }
  }
}
