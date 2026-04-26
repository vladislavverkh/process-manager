package dev.verkhovskiy.processmanager;

/** Поддерживаемые типы состояний процесса. */
public enum StateKind {
  ACTION,
  WAIT,
  DECISION,
  TERMINAL
}
