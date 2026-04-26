package dev.verkhovskiy.processmanager;

/** Predicate deciding whether a transition can be taken. */
@FunctionalInterface
public interface TransitionCondition<P> {

  boolean matches(TransitionContext<P> context);
}
