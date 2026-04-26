package dev.verkhovskiy.processmanager;

import java.time.Duration;

/** Планирует персистентные команды, которые обеспечивают исполнение процесса. */
public interface ProcessCommandScheduler {

  void schedule(ProcessCommand command, String partitionKey);

  void scheduleDelayed(ProcessCommand command, String partitionKey, Duration delay);
}
