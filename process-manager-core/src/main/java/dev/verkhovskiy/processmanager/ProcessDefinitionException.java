package dev.verkhovskiy.processmanager;

/** Invalid process definition or transition selection error. */
public class ProcessDefinitionException extends RuntimeException {

  public ProcessDefinitionException(String message) {
    super(message);
  }
}
