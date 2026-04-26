package dev.verkhovskiy.processmanager;

import java.util.Comparator;
import java.util.List;

/** Selects exactly one outgoing transition for a state. */
public class TransitionSelector {

  public <P> TransitionDefinition<P> select(
      StateDefinition<P> state, TransitionContext<P> context) {
    List<TransitionDefinition<P>> matches =
        state.transitions().stream()
            .filter(transition -> transition.condition().matches(context))
            .sorted(Comparator.comparingInt(TransitionDefinition::priority))
            .toList();
    if (matches.isEmpty()) {
      throw new ProcessDefinitionException("No transition matched for state: " + state.name());
    }
    if (matches.size() > 1 && matches.get(0).priority() == matches.get(1).priority()) {
      throw new ProcessDefinitionException(
          "Ambiguous transitions for state "
              + state.name()
              + " at priority "
              + matches.get(0).priority());
    }
    return matches.getFirst();
  }
}
