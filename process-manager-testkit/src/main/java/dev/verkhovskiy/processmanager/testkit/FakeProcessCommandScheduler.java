package dev.verkhovskiy.processmanager.testkit;

import dev.verkhovskiy.processmanager.ProcessCommand;
import dev.verkhovskiy.processmanager.ProcessCommandScheduler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** In-memory {@link ProcessCommandScheduler} для unit-тестов retry/resume/timeout scheduling. */
public final class FakeProcessCommandScheduler implements ProcessCommandScheduler {

  private final List<ScheduledCommand> commands = new ArrayList<>();

  @Override
  public void schedule(ProcessCommand command, String partitionKey) {
    commands.add(new ScheduledCommand(command, partitionKey, Duration.ZERO));
  }

  @Override
  public void scheduleDelayed(ProcessCommand command, String partitionKey, Duration delay) {
    commands.add(
        new ScheduledCommand(command, partitionKey, delay == null ? Duration.ZERO : delay));
  }

  public List<ScheduledCommand> commands() {
    return List.copyOf(commands);
  }

  public ScheduledCommand command(int index) {
    return commands.get(index);
  }

  public ScheduledCommand lastCommand() {
    if (commands.isEmpty()) {
      throw new IllegalStateException("No process commands were scheduled");
    }
    return commands.getLast();
  }

  public int size() {
    return commands.size();
  }

  public boolean isEmpty() {
    return commands.isEmpty();
  }

  public void clear() {
    commands.clear();
  }

  public record ScheduledCommand(ProcessCommand command, String partitionKey, Duration delay) {}
}
