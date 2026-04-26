package dev.verkhovskiy.processmanager;

import java.time.Duration;

/** Schedules durable commands that drive process execution. */
public interface ProcessCommandScheduler {

  void schedule(ProcessCommand command, String partitionKey);

  void scheduleDelayed(ProcessCommand command, String partitionKey, Duration delay);
}
