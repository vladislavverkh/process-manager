package dev.verkhovskiy.processmanager;

/** Вычисляет ключ корреляции для WAIT-состояния. */
@FunctionalInterface
public interface CorrelationKeyResolver<P> {

  String resolve(ProcessContext<P> context);
}
