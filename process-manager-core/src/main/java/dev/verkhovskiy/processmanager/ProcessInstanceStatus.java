package dev.verkhovskiy.processmanager;

/** Runtime status of a process instance. */
public enum ProcessInstanceStatus {
  RUNNING,
  WAITING,
  COMPLETED,
  FAILED,
  CANCELLED
}
