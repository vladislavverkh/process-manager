package dev.verkhovskiy.processmanager.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.verkhovskiy.processmanager.postgres.PostgresProcessRepository;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProcessRetentionCleanupTest {

  @Mock private PostgresProcessRepository processRepository;
  @Mock private ProcessManagerMetrics metrics;

  @Captor private ArgumentCaptor<Duration> durationCaptor;

  @Test
  void deletesExpiredTerminalInstancesAndRecordsMetrics() {
    when(processRepository.deleteExpiredTerminalInstances(50)).thenReturn(12);

    ProcessRetentionCleanup cleanup = new ProcessRetentionCleanup(processRepository, 100, metrics);

    int deleted = cleanup.runOnce(50);

    assertThat(deleted).isEqualTo(12);
    verify(processRepository).deleteExpiredTerminalInstances(50);
    verify(metrics).recordRetentionCleanup(durationCaptor.capture(), eq(12), eq("success"));
    assertThat(durationCaptor.getValue().isNegative()).isFalse();
  }
}
