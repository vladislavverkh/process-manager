# Roadmap

Документ фиксирует ближайшие этапы развития, чтобы не смешивать уже реализованный код и целевой
дизайн.

## Сейчас реализовано

- Multi-module Gradle project.
- Core contracts:
  - `ProcessDefinition`
  - `StateDefinition`
  - `TransitionDefinition`
  - `TransitionSelector`
  - `StepResult`
  - `RetryPolicy`
  - `ProcessRetention`
- PostgreSQL schema:
  - `pm_process_instance`
  - `pm_process_wait`
  - `pm_process_event_inbox`
  - `pm_process_history`
- Repository для основных SQL-операций.
- Task queue adapter:
  - `TaskQueueProcessCommandScheduler`
  - `ProcessCommandTaskHandler`
- Spring Boot autoconfiguration.
- Basic execution loop:
  - load instance for update;
  - resolve definition by `processType + definitionVersion`;
  - deserialize typed payload and variables;
  - execute `ACTION`;
  - persist action result data and explicit variable updates;
  - persist last trigger data for action/event/timeout/retry;
  - track process/state deadlines;
  - schedule timeout commands from deadline watchdog only after deadline expiration;
  - register `WAIT`;
  - evaluate `DECISION`;
  - enter terminal state;
  - write history;
  - skip stale commands by `expectedVersion`.
- Process definition validation:
  - required ACTION/WAIT/TERMINAL fields;
  - duplicate transition priorities;
  - unreachable states;
  - path from reachable states to terminal states.
- Basic testkit assertions for process definitions.

## Ближайший MVP

1. Довести retry execution:
   - formalize retry counters/metadata;
   - route exhausted retry outcomes;
   - add metrics for skipped stale commands.

2. Довести payload mapper:
   - validation error handling;
   - отделить `payload_schema_version` от `definition_version`.

3. Реализовать stale command handling:
   - учитывать `expectedVersion`;
   - безопасно пропускать устаревшие retry/timeout/resume commands;
   - добавить метрики.

4. Довести deadline watchdog:
   - добавить метрики по просроченным process/state deadlines;
   - добавить рекомендуемую scheduled-конфигурацию;
   - определить политику escalation для процессов без timeout target.

## Следующие этапы

1. Kafka integration module:
   - generic listener adapter;
   - idempotency key;
   - mapping Kafka record -> `ExternalEvent`.

2. Admin/observability:
   - summary по process instances;
   - список waiting instances;
   - manual retry/cancel;
   - Micrometer metrics.

3. Retention job:
   - scheduled cleanup terminal instances;
   - cleanup metrics;
   - batch-size property.

4. Testkit:
   - deterministic definition runner без PostgreSQL;
   - fake command scheduler.

## Позже

- Payload upcasters для schema evolution.
- Operator REST API.
- Partitioned history tables.
- YAML/JSON process definitions, если Java DSL станет недостаточным.
- Outbox для команд во внешние системы, если понадобится exactly-once интеграция.
