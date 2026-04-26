package dev.verkhovskiy.processmanager;

/** Ошибка невалидного описания процесса или выбора перехода. */
public class ProcessDefinitionException extends RuntimeException {

  public ProcessDefinitionException(String message) {
    super(message);
  }
}
