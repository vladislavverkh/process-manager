package dev.verkhovskiy.processmanager;

/** Предикат, определяющий, можно ли выполнить переход. */
@FunctionalInterface
public interface TransitionCondition<P> {

  boolean matches(TransitionContext<P> context);
}
