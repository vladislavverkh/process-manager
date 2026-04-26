package dev.verkhovskiy.processmanager;

import java.time.Duration;
import java.util.LinkedHashMap;
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

  /** Результат с явными изменениями variables. */
  record WithVariables(StepResult delegate, Map<String, Object> variables) implements StepResult {
    public WithVariables {
      if (delegate == null) {
        throw new IllegalArgumentException("delegate must be set");
      }
      variables = Map.copyOf(variables == null ? Map.of() : variables);
    }

    @Override
    public StepResult baseResult() {
      return delegate.baseResult();
    }

    @Override
    public Map<String, Object> variableUpdates() {
      Map<String, Object> updates = new LinkedHashMap<>(delegate.variableUpdates());
      updates.putAll(variables);
      return Map.copyOf(updates);
    }
  }

  /** Возвращает базовый result без decorator-оберток. */
  default StepResult baseResult() {
    return this;
  }

  /** Возвращает явные изменения variables, которые action просит сохранить. */
  default Map<String, Object> variableUpdates() {
    return Map.of();
  }

  /** Возвращает result с дополнительной variable. */
  default StepResult withVariable(String name, Object value) {
    return withVariables(Map.of(name, value));
  }

  /** Возвращает result с дополнительными variables. */
  default StepResult withVariables(Map<String, Object> variables) {
    return new WithVariables(this, variables);
  }

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
