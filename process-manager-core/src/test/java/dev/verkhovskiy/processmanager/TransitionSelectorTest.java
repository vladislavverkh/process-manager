package dev.verkhovskiy.processmanager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransitionSelectorTest {

  private final TransitionSelector selector = new TransitionSelector();

  @Test
  void selectsFirstMatchingTransitionByPriority() {
    StateDefinition<Map<String, Object>> state =
        new StateDefinition<>(
            "CHECK",
            StateKind.DECISION,
            null,
            null,
            null,
            null,
            RetryPolicy.none(),
            null,
            List.of(
                new TransitionDefinition<>("large", "MANUAL", 10, ctx -> true),
                new TransitionDefinition<>("regular", "AUTO", 20, ctx -> true)));

    TransitionDefinition<Map<String, Object>> selected = selector.select(state, context());

    assertThat(selected.name()).isEqualTo("large");
    assertThat(selected.targetState()).isEqualTo("MANUAL");
  }

  @Test
  void rejectsAmbiguousTransitionsAtSamePriority() {
    StateDefinition<Map<String, Object>> state =
        new StateDefinition<>(
            "CHECK",
            StateKind.DECISION,
            null,
            null,
            null,
            null,
            RetryPolicy.none(),
            null,
            List.of(
                new TransitionDefinition<>("a", "A", 1, ctx -> true),
                new TransitionDefinition<>("b", "B", 1, ctx -> true)));

    assertThatThrownBy(() -> selector.select(state, context()))
        .isInstanceOf(ProcessDefinitionException.class)
        .hasMessageContaining("Ambiguous transitions");
  }

  @Test
  void supportsOtherwiseFallback() {
    StateDefinition<Map<String, Object>> state =
        new StateDefinition<>(
            "CHECK",
            StateKind.DECISION,
            null,
            null,
            null,
            null,
            RetryPolicy.none(),
            null,
            List.of(
                new TransitionDefinition<>("specific", "A", 1, ctx -> false),
                TransitionDefinition.always("otherwise", "B")));

    assertThat(selector.select(state, context()).targetState()).isEqualTo("B");
  }

  private static TransitionContext<Map<String, Object>> context() {
    return new TransitionContext<>(
        UUID.randomUUID(),
        "payment",
        1,
        "CHECK",
        "payment-1",
        Map.of(),
        ProcessVariables.empty(),
        StepResult.success("OK"),
        null,
        Instant.parse("2026-04-26T12:00:00Z"));
  }
}
