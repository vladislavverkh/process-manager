package dev.verkhovskiy.processmanager.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.verkhovskiy.processmanager.ProcessCommand;
import dev.verkhovskiy.processmanager.ProcessCommandReason;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FakeProcessCommandSchedulerTest {

  @Test
  void recordsImmediateAndDelayedCommands() {
    UUID instanceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    FakeProcessCommandScheduler scheduler = new FakeProcessCommandScheduler();
    ProcessCommand resume = new ProcessCommand(instanceId, ProcessCommandReason.RESUME, -1);
    ProcessCommand retry = new ProcessCommand(instanceId, ProcessCommandReason.RETRY, 2);

    scheduler.schedule(resume, "payment:payment-1");
    scheduler.scheduleDelayed(retry, "payment:payment-1", Duration.ofSeconds(5));

    assertThat(scheduler.commands())
        .containsExactly(
            new FakeProcessCommandScheduler.ScheduledCommand(
                resume, "payment:payment-1", Duration.ZERO),
            new FakeProcessCommandScheduler.ScheduledCommand(
                retry, "payment:payment-1", Duration.ofSeconds(5)));
    assertThat(scheduler.lastCommand().command()).isEqualTo(retry);
    assertThat(scheduler.lastCommand().delay()).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void canBeClearedBetweenAssertions() {
    FakeProcessCommandScheduler scheduler = new FakeProcessCommandScheduler();
    scheduler.schedule(
        new ProcessCommand(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            ProcessCommandReason.PROCESS_TIMEOUT,
            7),
        "payment:payment-1");

    scheduler.clear();

    assertThat(scheduler).extracting(FakeProcessCommandScheduler::isEmpty).isEqualTo(true);
    assertThat(scheduler.size()).isZero();
  }
}
