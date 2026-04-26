package dev.verkhovskiy.processmanager;

/** Business action executed by an ACTION state. */
@FunctionalInterface
public interface ProcessAction<P> {

  StepResult execute(ProcessContext<P> context) throws Exception;
}
