package dev.verkhovskiy.processmanager.postgres;

import dev.verkhovskiy.processmanager.ProcessInstanceStatus;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** PostgreSQL-репозиторий для экземпляров процессов, ожиданий, входящих событий и истории. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "NamedParameterJdbcTemplate является внедренным инфраструктурным Spring-бином.")
public class PostgresProcessRepository {

  private static final RowMapper<StoredProcessInstance> INSTANCE_MAPPER =
      (rs, rowNum) ->
          new StoredProcessInstance(
              rs.getObject("instance_id", UUID.class),
              rs.getString("process_type"),
              rs.getInt("definition_version"),
              rs.getInt("payload_schema_version"),
              rs.getString("business_key"),
              rs.getString("state"),
              ProcessInstanceStatus.valueOf(rs.getString("status")),
              rs.getString("payload_json"),
              rs.getString("variables_json"),
              toInstant(rs, "started_at"),
              toInstant(rs, "updated_at"),
              toInstant(rs, "completed_at"),
              toInstant(rs, "delete_after"),
              rs.getLong("version"));

  private static final RowMapper<StoredProcessWait> WAIT_MAPPER =
      (rs, rowNum) ->
          new StoredProcessWait(
              rs.getObject("wait_id", UUID.class),
              rs.getObject("instance_id", UUID.class),
              rs.getString("process_type"),
              rs.getString("state"),
              rs.getString("event_type"),
              rs.getString("correlation_key"),
              toInstant(rs, "expires_at"),
              toInstant(rs, "created_at"));

  private static final RowMapper<StoredProcessEvent> EVENT_MAPPER =
      (rs, rowNum) ->
          new StoredProcessEvent(
              rs.getObject("event_id", UUID.class),
              rs.getString("event_type"),
              rs.getString("correlation_key"),
              rs.getString("payload_json"),
              toInstant(rs, "received_at"),
              toInstant(rs, "consumed_at"));

  private final NamedParameterJdbcTemplate jdbc;

  public PostgresProcessRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Вставляет новый экземпляр процесса. */
  public void insertInstance(StoredProcessInstance instance) {
    jdbc.update(
        """
            insert into pm_process_instance(
                instance_id,
                process_type,
                definition_version,
                payload_schema_version,
                business_key,
                state,
                status,
                payload_json,
                variables_json,
                started_at,
                updated_at,
                completed_at,
                delete_after,
                version
            )
            values(
                :instanceId,
                :processType,
                :definitionVersion,
                :payloadSchemaVersion,
                :businessKey,
                :state,
                :status,
                cast(:payloadJson as jsonb),
                cast(:variablesJson as jsonb),
                :startedAt,
                :updatedAt,
                :completedAt,
                :deleteAfter,
                :version
            )
            """,
        instanceParameters(instance));
  }

  /** Находит и блокирует экземпляр процесса для исполнения. */
  public Optional<StoredProcessInstance> findInstanceForUpdate(UUID instanceId) {
    List<StoredProcessInstance> rows =
        jdbc.query(
            """
            select instance_id,
                   process_type,
                   definition_version,
                   payload_schema_version,
                   business_key,
                   state,
                   status,
                   payload_json::text as payload_json,
                   variables_json::text as variables_json,
                   started_at,
                   updated_at,
                   completed_at,
                   delete_after,
                   version
              from pm_process_instance
             where instance_id = :instanceId
             for update
            """,
            new MapSqlParameterSource("instanceId", instanceId),
            INSTANCE_MAPPER);
    return rows.stream().findFirst();
  }

  /** Обновляет состояние, статус и переменные после перехода. */
  public int updateExecutionState(
      UUID instanceId,
      long expectedVersion,
      String state,
      ProcessInstanceStatus status,
      String variablesJson,
      Instant completedAt,
      Instant deleteAfter) {
    return jdbc.update(
        """
            with runtime_clock as (
                select clock_timestamp() as now
            )
            update pm_process_instance
               set state = :state,
                   status = :status,
                   variables_json = cast(:variablesJson as jsonb),
                   updated_at = runtime_clock.now,
                   completed_at = :completedAt,
                   delete_after = :deleteAfter,
                   version = version + 1
              from runtime_clock
             where instance_id = :instanceId
               and version = :expectedVersion
            """,
        new MapSqlParameterSource()
            .addValue("instanceId", instanceId)
            .addValue("expectedVersion", expectedVersion)
            .addValue("state", state)
            .addValue("status", status.name())
            .addValue("variablesJson", variablesJson)
            .addValue("completedAt", toOffsetDateTime(completedAt))
            .addValue("deleteAfter", toOffsetDateTime(deleteAfter)));
  }

  /** Регистрирует или заменяет точку ожидания внешнего события. */
  public void upsertWait(StoredProcessWait wait) {
    jdbc.update(
        """
            insert into pm_process_wait(
                wait_id,
                instance_id,
                process_type,
                state,
                event_type,
                correlation_key,
                expires_at,
                created_at
            )
            values(
                :waitId,
                :instanceId,
                :processType,
                :state,
                :eventType,
                :correlationKey,
                :expiresAt,
                :createdAt
            )
            on conflict (event_type, correlation_key, instance_id)
            do update
                  set state = excluded.state,
                      expires_at = excluded.expires_at
            """,
        new MapSqlParameterSource()
            .addValue("waitId", wait.waitId())
            .addValue("instanceId", wait.instanceId())
            .addValue("processType", wait.processType())
            .addValue("state", wait.state())
            .addValue("eventType", wait.eventType())
            .addValue("correlationKey", wait.correlationKey())
            .addValue("expiresAt", toOffsetDateTime(wait.expiresAt()))
            .addValue("createdAt", toOffsetDateTime(wait.createdAt())));
  }

  /** Находит ожидания процесса, подходящие под внешнее событие. */
  public List<StoredProcessWait> findWaits(String eventType, String correlationKey) {
    return jdbc.query(
        """
            select wait_id,
                   instance_id,
                   process_type,
                   state,
                   event_type,
                   correlation_key,
                   expires_at,
                   created_at
              from pm_process_wait
             where event_type = :eventType
               and correlation_key = :correlationKey
             order by created_at
            """,
        new MapSqlParameterSource()
            .addValue("eventType", eventType)
            .addValue("correlationKey", correlationKey),
        WAIT_MAPPER);
  }

  /** Удаляет все ожидания для экземпляра процесса. */
  public int deleteWaits(UUID instanceId) {
    return jdbc.update(
        "delete from pm_process_wait where instance_id = :instanceId",
        new MapSqlParameterSource("instanceId", instanceId));
  }

  /** Сохраняет входящее внешнее событие в таблицу входящих событий. */
  public void insertEvent(
      UUID eventId, String eventType, String correlationKey, String payloadJson) {
    jdbc.update(
        """
            with runtime_clock as (
                select clock_timestamp() as now
            )
            insert into pm_process_event_inbox(
                event_id,
                event_type,
                correlation_key,
                payload_json,
                received_at
            )
            select
                :eventId,
                :eventType,
                :correlationKey,
                cast(:payloadJson as jsonb),
                runtime_clock.now
              from runtime_clock
            """,
        new MapSqlParameterSource()
            .addValue("eventId", eventId)
            .addValue("eventType", eventType)
            .addValue("correlationKey", correlationKey)
            .addValue("payloadJson", payloadJson));
  }

  /** Находит и блокирует первое необработанное событие, подходящее под точку ожидания. */
  public Optional<StoredProcessEvent> findUnconsumedEventForUpdate(
      String eventType, String correlationKey) {
    List<StoredProcessEvent> rows =
        jdbc.query(
            """
            select event_id,
                   event_type,
                   correlation_key,
                   payload_json::text as payload_json,
                   received_at,
                   consumed_at
              from pm_process_event_inbox
             where event_type = :eventType
               and correlation_key = :correlationKey
               and consumed_at is null
             order by received_at, event_id
             limit 1
             for update skip locked
            """,
            new MapSqlParameterSource()
                .addValue("eventType", eventType)
                .addValue("correlationKey", correlationKey),
            EVENT_MAPPER);
    return rows.stream().findFirst();
  }

  /** Помечает входящее событие как обработанное. */
  public int markEventConsumed(UUID eventId) {
    return jdbc.update(
        """
            with runtime_clock as (
                select clock_timestamp() as now
            )
            update pm_process_event_inbox
               set consumed_at = runtime_clock.now
              from runtime_clock
             where event_id = :eventId
               and consumed_at is null
            """,
        new MapSqlParameterSource("eventId", eventId));
  }

  /** Вставляет запись истории. */
  public void insertHistory(ProcessHistoryRecord history) {
    jdbc.update(
        """
            insert into pm_process_history(
                history_id,
                instance_id,
                process_type,
                from_state,
                to_state,
                transition_name,
                trigger_type,
                trigger_json,
                created_at
            )
            values(
                :historyId,
                :instanceId,
                :processType,
                :fromState,
                :toState,
                :transitionName,
                :triggerType,
                cast(:triggerJson as jsonb),
                :createdAt
            )
            """,
        new MapSqlParameterSource()
            .addValue("historyId", history.historyId())
            .addValue("instanceId", history.instanceId())
            .addValue("processType", history.processType())
            .addValue("fromState", history.fromState())
            .addValue("toState", history.toState())
            .addValue("transitionName", history.transitionName())
            .addValue("triggerType", history.triggerType())
            .addValue("triggerJson", history.triggerJson())
            .addValue("createdAt", toOffsetDateTime(history.createdAt())));
  }

  /** Удаляет финальные экземпляры процессов с истекшим сроком хранения. */
  public int deleteExpiredTerminalInstances(int limit) {
    return jdbc.update(
        """
            with runtime_clock as (
                select clock_timestamp() as now
            ),
            expired as (
                select instance_id
                  from pm_process_instance
                 cross join runtime_clock
                 where status in ('COMPLETED', 'FAILED', 'CANCELLED')
                   and delete_after is not null
                   and delete_after <= runtime_clock.now
                 order by delete_after, instance_id
                 for update of pm_process_instance skip locked
                 limit :limit
            )
            delete from pm_process_instance i
             using expired e
             where i.instance_id = e.instance_id
            """,
        new MapSqlParameterSource("limit", limit));
  }

  private static MapSqlParameterSource instanceParameters(StoredProcessInstance instance) {
    return new MapSqlParameterSource()
        .addValue("instanceId", instance.instanceId())
        .addValue("processType", instance.processType())
        .addValue("definitionVersion", instance.definitionVersion())
        .addValue("payloadSchemaVersion", instance.payloadSchemaVersion())
        .addValue("businessKey", instance.businessKey())
        .addValue("state", instance.state())
        .addValue("status", instance.status().name())
        .addValue("payloadJson", instance.payloadJson())
        .addValue("variablesJson", instance.variablesJson())
        .addValue("startedAt", toOffsetDateTime(instance.startedAt()))
        .addValue("updatedAt", toOffsetDateTime(instance.updatedAt()))
        .addValue("completedAt", toOffsetDateTime(instance.completedAt()))
        .addValue("deleteAfter", toOffsetDateTime(instance.deleteAfter()))
        .addValue("version", instance.version());
  }

  private static Instant toInstant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static OffsetDateTime toOffsetDateTime(Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
