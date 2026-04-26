package dev.verkhovskiy.processmanager;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Неизменяемые переменные процесса, доступные действиям и условиям переходов. */
public record ProcessVariables(Map<String, Object> values) {

  public ProcessVariables {
    values = Map.copyOf(values == null ? Map.of() : values);
  }

  /** Возвращает пустые переменные. */
  public static ProcessVariables empty() {
    return new ProcessVariables(Map.of());
  }

  /** Возвращает переменные с добавленным или замененным значением. */
  public ProcessVariables with(String name, Object value) {
    Map<String, Object> updated = new LinkedHashMap<>(values);
    updated.put(name, value);
    return new ProcessVariables(updated);
  }

  /** Возвращает переменные без указанного значения. */
  public ProcessVariables without(String name) {
    if (!values.containsKey(name)) {
      return this;
    }
    Map<String, Object> updated = new LinkedHashMap<>(values);
    updated.remove(name);
    return new ProcessVariables(updated);
  }

  /** Возвращает низкоуровневое значение переменной. */
  public Optional<Object> get(String name) {
    return Optional.ofNullable(values.get(name));
  }

  /** Возвращает переменную как строку, если она есть. */
  public Optional<String> string(String name) {
    return get(name).map(Object::toString);
  }

  /** Возвращает переменную как десятичное число, если она есть и может быть разобрана. */
  public Optional<BigDecimal> decimal(String name) {
    return get(name).map(ProcessVariables::toDecimal);
  }

  /** Возвращает переменную как целое число, если она есть и может быть разобрана. */
  public Optional<Integer> integer(String name) {
    return get(name)
        .map(value -> value instanceof Number n ? n.intValue() : Integer.valueOf(value.toString()));
  }

  private static BigDecimal toDecimal(Object value) {
    if (value instanceof BigDecimal decimal) {
      return decimal;
    }
    if (value instanceof Number number) {
      return BigDecimal.valueOf(number.doubleValue());
    }
    return new BigDecimal(value.toString());
  }
}
