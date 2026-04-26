package dev.verkhovskiy.processmanager;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Immutable process variables visible to actions and transition conditions. */
public record ProcessVariables(Map<String, Object> values) {

  public ProcessVariables {
    values = Map.copyOf(values == null ? Map.of() : values);
  }

  /** Returns empty variables. */
  public static ProcessVariables empty() {
    return new ProcessVariables(Map.of());
  }

  /** Returns variables with an additional or replaced value. */
  public ProcessVariables with(String name, Object value) {
    Map<String, Object> updated = new LinkedHashMap<>(values);
    updated.put(name, value);
    return new ProcessVariables(updated);
  }

  /** Returns raw variable value. */
  public Optional<Object> get(String name) {
    return Optional.ofNullable(values.get(name));
  }

  /** Returns variable as string when present. */
  public Optional<String> string(String name) {
    return get(name).map(Object::toString);
  }

  /** Returns variable as decimal when present and parseable. */
  public Optional<BigDecimal> decimal(String name) {
    return get(name).map(ProcessVariables::toDecimal);
  }

  /** Returns variable as int when present and parseable. */
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
