package dev.verkhovskiy.processmanager;

/** Ошибка сериализации, десериализации или проверки версии process payload. */
public class ProcessPayloadMappingException extends IllegalArgumentException {

  public ProcessPayloadMappingException(String message) {
    super(message);
  }

  public ProcessPayloadMappingException(String message, Throwable cause) {
    super(message, cause);
  }
}
