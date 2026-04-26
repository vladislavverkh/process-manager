package dev.verkhovskiy.processmanager;

import java.time.Duration;
import java.util.Map;

/** Результат, который ACTION-состояние возвращает перед выбором перехода. */
public sealed interface StepResult {

  /** Успешный результат действия с бизнес-кодом и структурированными данными. */
  record Success(String code, Map<String, Object> data) implements StepResult {
    public Success {
      data = Map.copyOf(data == null ? Map.of() : data);
    }
  }

  /** Бизнес-отказ, который должен маршрутизироваться обычными переходами процесса. */
  record BusinessFailure(String code, Map<String, Object> data) implements StepResult {
    public BusinessFailure {
      data = Map.copyOf(data == null ? Map.of() : data);
    }
  }

  /** Техническая ошибка, которую можно повторить. */
  record RetryableFailure(String code, String message) implements StepResult {}

  /** Техническая ошибка, которую нельзя повторить. */
  record FatalFailure(String code, String message) implements StepResult {}

  /** Действие просит среду выполнения ожидать внешнее событие. */
  record AwaitEvent(String eventType, String correlationKey, Duration timeout)
      implements StepResult {}

  /** Создает успешный результат без дополнительных данных. */
  static Success success(String code) {
    return new Success(code, Map.of());
  }

  /** Создает успешный результат с дополнительными данными. */
  static Success success(String code, Map<String, Object> data) {
    return new Success(code, data);
  }

  /** Создает результат бизнес-отказа. */
  static BusinessFailure businessFailure(String code, Map<String, Object> data) {
    return new BusinessFailure(code, data);
  }

  /** Создает техническую ошибку, которую можно повторить. */
  static RetryableFailure retryableFailure(String code, String message) {
    return new RetryableFailure(code, message);
  }

  /** Создает фатальную техническую ошибку. */
  static FatalFailure fatalFailure(String code, String message) {
    return new FatalFailure(code, message);
  }

  /** Создает результат ожидания внешнего события. */
  static AwaitEvent awaitEvent(String eventType, String correlationKey, Duration timeout) {
    return new AwaitEvent(eventType, correlationKey, timeout);
  }
}
