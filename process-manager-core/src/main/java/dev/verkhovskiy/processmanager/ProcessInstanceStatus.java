package dev.verkhovskiy.processmanager;

/** Статус выполнения экземпляра процесса. */
public enum ProcessInstanceStatus {
  RUNNING,
  WAITING,
  COMPLETED,
  FAILED,
  CANCELLED
}
