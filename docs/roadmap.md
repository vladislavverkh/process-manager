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
  - register `WAIT`;
  - evaluate `DECISION`;
  - enter terminal state;
  - write history;
  - skip stale commands by `expectedVersion`.

## Ближайший MVP

1. Довести retry и timeout execution:
   - formalize retry counters/metadata;
   - route exhausted retry outcomes;
   - choose timeout transition explicitly;
   - add metrics for skipped stale commands.

2. Довести payload mapper:
   - validation error handling;
   - отделить `payload_schema_version` от `definition_version`.

3. Реализовать variable updates:
   - action result data -> variables;
   - explicit API для изменения variables из action;
   - хранение last trigger data.

4. Реализовать stale command handling:
   - учитывать `expectedVersion`;
   - безопасно пропускать устаревшие retry/timeout/resume commands;
   - добавить метрики.

5. Реализовать wait timeout:
   - сохранять `expires_at`;
   - планировать `TIMEOUT` command;
   - выбирать timeout transition.

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

3. Process definition validation:
   - unreachable states;
   - missing terminal states;
   - duplicate transition priorities;
   - WAIT state without timeout policy, если timeout обязателен.

4. Retention job:
   - scheduled cleanup terminal instances;
   - cleanup metrics;
   - batch-size property.

5. Testkit:
   - deterministic definition runner без PostgreSQL;
   - assertions для transition selection;
   - fake command scheduler.

## Позже

- Payload upcasters для schema evolution.
- Operator REST API.
- Partitioned history tables.
- YAML/JSON process definitions, если Java DSL станет недостаточным.
- Outbox для команд во внешние системы, если понадобится exactly-once интеграция.
