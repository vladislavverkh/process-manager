package dev.verkhovskiy.processmanager;

/** Resolves correlation key for a WAIT state. */
@FunctionalInterface
public interface CorrelationKeyResolver<P> {

  String resolve(ProcessContext<P> context);
}
