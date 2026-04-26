package dev.verkhovskiy.processmanager;

/** Причина, по которой запланировано исполнение процесса. */
public enum ProcessCommandReason {
  START,
  RESUME,
  RETRY,
  TIMEOUT
}
