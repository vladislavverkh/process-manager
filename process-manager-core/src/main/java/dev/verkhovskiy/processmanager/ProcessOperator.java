package dev.verkhovskiy.processmanager;

import java.util.UUID;

/** API ручных операторских операций над экземплярами процессов. */
public interface ProcessOperator {

  /**
   * Отменяет активный экземпляр процесса.
   *
   * @return {@code true}, если процесс был переведен в {@link ProcessInstanceStatus#CANCELLED}
   */
  boolean cancel(UUID instanceId, String reason);

  /**
   * Планирует ручное возобновление активного экземпляра процесса через очередь команд.
   *
   * @return {@code true}, если команда была запланирована
   */
  boolean scheduleResume(UUID instanceId);

  /**
   * Планирует ручной повтор текущего RUNNING-состояния через очередь команд.
   *
   * @return {@code true}, если команда была запланирована
   */
  boolean scheduleRetry(UUID instanceId);
}
