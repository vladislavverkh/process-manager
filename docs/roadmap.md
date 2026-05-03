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
- Optional task queue adapter classes:
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
- Idempotency:
  - repeated `start(processType, businessKey, payload)` returns active instance;
  - optional `signal(..., idempotencyKey, payload)` deduplicates inbox events.
- Inspection API:
  - find process instance by id;
  - find active instance by `processType + businessKey`;
  - list instances by filters;
  - read waits and history for diagnostics.
- Operator API:
  - cancel active process instance;
  - schedule manual resume;
  - schedule manual retry for RUNNING instances.
- Operator REST API:
  - details and list endpoints for process instances;
  - manual cancel/resume/retry endpoints;
  - separate `process-manager-rest` module.
- Observability:
  - Micrometer runtime metrics;
  - metrics for stale, terminal and missing commands;
  - deadline watchdog metrics;
  - retention cleanup metrics;
  - PostgreSQL-backed gauges for active instances, waits, unconsumed events and overdue deadlines;
  - Prometheus/Grafana setup in sample app.
- Retention cleanup:
  - scheduled cleanup runtime component;
  - cleanup batch-size property;
  - sample scheduled cleanup job.
- Process definition validation:
  - required ACTION/WAIT/TERMINAL fields;
  - duplicate transition priorities;
  - unreachable states;
  - path from reachable states to terminal states.
- Basic testkit assertions for process definitions.

## Ближайший MVP

1. Довести retry execution:
   - route exhausted retry outcomes;
   - document retry exhaustion semantics.

2. Довести payload mapper:
   - validation error handling;
   - отделить `payload_schema_version` от `definition_version`.

3. Довести deadline watchdog:
   - определить политику escalation для процессов без timeout target.

## Следующие этапы

1. Admin/observability:
   - aggregated summary по process instances;
   - alerting examples for Prometheus/Grafana.

2. Testkit:
   - deterministic definition runner без PostgreSQL;
   - fake command scheduler.

## Позже

- Payload upcasters для schema evolution.
- Partitioned history tables.
- YAML/JSON process definitions, если Java DSL станет недостаточным.
- Outbox для команд во внешние системы, если понадобится exactly-once интеграция.
