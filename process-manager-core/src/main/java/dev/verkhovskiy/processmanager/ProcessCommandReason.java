package dev.verkhovskiy.processmanager;

/** Причина, по которой запланировано исполнение процесса. */
public enum ProcessCommandReason {
  START,
  RESUME,
  RETRY,
  TIMEOUT,
  PROCESS_TIMEOUT,
  STATE_TIMEOUT
}
