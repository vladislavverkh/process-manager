package dev.verkhovskiy.processmanager;

/** Одна проблема, найденная при проверке описания процесса. */
public record ProcessDefinitionProblem(String code, String state, String message) {

  public ProcessDefinitionProblem {
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("code must be set");
    }
    if (message == null || message.isBlank()) {
      throw new IllegalArgumentException("message must be set");
    }
  }

  /** Создает проблему, относящуюся ко всему process definition. */
  public static ProcessDefinitionProblem global(String code, String message) {
    return new ProcessDefinitionProblem(code, null, message);
  }

  /** Создает проблему, относящуюся к конкретному state. */
  public static ProcessDefinitionProblem state(String code, String state, String message) {
    return new ProcessDefinitionProblem(code, state, message);
  }
}
