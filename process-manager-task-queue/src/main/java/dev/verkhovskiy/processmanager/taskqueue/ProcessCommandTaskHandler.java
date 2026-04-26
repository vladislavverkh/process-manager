package dev.verkhovskiy.processmanager.taskqueue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.verkhovskiy.processmanager.ProcessCommand;
import dev.verkhovskiy.processmanager.ProcessManager;
import dev.verkhovskiy.taskqueue.domain.QueuedTask;
import dev.verkhovskiy.taskqueue.handler.TaskHandler;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/** Обработчик очереди задач, который возобновляет экземпляр процесса. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification =
        "ProcessManager и ObjectMapper являются внедренными инфраструктурными Spring-бинами.")
public class ProcessCommandTaskHandler implements TaskHandler {

  private final ProcessManager processManager;
  private final ObjectMapper objectMapper;

  public ProcessCommandTaskHandler(ProcessManager processManager, ObjectMapper objectMapper) {
    this.processManager = processManager;
    this.objectMapper = objectMapper;
  }

  @Override
  public String taskType() {
    return TaskQueueProcessCommandScheduler.TASK_TYPE;
  }

  @Override
  public void handle(QueuedTask task) throws Exception {
    ProcessCommand command = objectMapper.readValue(task.payload(), ProcessCommand.class);
    processManager.resume(command);
  }
}
