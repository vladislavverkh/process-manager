package dev.verkhovskiy.processmanager;

/** Reason why process execution is scheduled. */
public enum ProcessCommandReason {
  START,
  RESUME,
  RETRY,
  TIMEOUT
}
