# Архитектура

`process-manager` разделяет описание бизнес-сценариев, durable state и асинхронное исполнение.

Главный принцип: PostgreSQL хранит единственное актуальное состояние процесса, а очередь хранит
только намерение продолжить процесс.

## Модули

| Модуль | Ответственность |
| --- | --- |
| `process-manager-core` | Process definition, state model, conditional transitions, retry/retention contracts |
| `process-manager-postgres` | Таблицы и repository для process instances, waits, event inbox и history |
| `process-manager-task-queue` | Adapter, который ставит process commands в `task-queue-postgres` |
| `process-manager-spring-boot-starter` | Autoconfiguration runtime-компонентов |
| `process-manager-testkit` | Assertions/helpers для тестирования process definitions |

## Компоненты

```text
Application
  |
  | start / signal / resume
  v
ProcessManager
  |
  | loads definition
  v
ProcessDefinitionRegistry
  |
  | persists state, waits, inbox, history
  v
PostgresProcessRepository
  |
  | schedules resume/retry/timeout command
  v
ProcessCommandScheduler
  |
  | task type: process-manager.command
  v
task-queue-postgres
```

## Runtime flow

### Start

1. Caller invokes `ProcessManager.start(processType, businessKey, payload)`.
2. Runtime finds latest `ProcessDefinition` for `processType`.
3. Runtime creates `pm_process_instance` with initial state.
4. Runtime schedules `ProcessCommand(reason=START)` through `ProcessCommandScheduler`.
5. Task queue worker later executes the command and resumes the instance.

Current implementation creates the instance, schedules the command and lets the runtime execute
state transitions from the stored instance state.

### External signal

1. Caller or Kafka adapter invokes `ProcessManager.signal(eventType, correlationKey, payload)`.
2. Runtime stores the event in `pm_process_event_inbox`.
3. Runtime finds matching rows in `pm_process_wait`.
4. Runtime schedules resume commands for matched instances.

### Resume

1. `ProcessCommandTaskHandler` receives `process-manager.command` from task queue.
2. It deserializes `ProcessCommand`.
3. It calls `ProcessManager.resume(instanceId)`.
4. Runtime locks the instance, checks command version, evaluates current state and executes the next
   transition.

Current implementation handles ACTION, WAIT, DECISION and TERMINAL states in the PostgreSQL-backed
runtime. Retry and timeout routing are intentionally still limited and tracked in the roadmap.

## Почему task-queue-postgres не хранит process payload

Queue payload должен быть маленьким и техническим:

```json
{
  "instanceId": "018f0000-0000-7000-8000-000000000001",
  "reason": "RESUME",
  "expectedVersion": 3
}
```

Истинное состояние процесса хранится в `pm_process_instance`. Это защищает от устаревшего payload в
очереди, упрощает retry и делает resume идемпотентнее.

## Границы ответственности

`process-manager` отвечает за:

- состояние процесса;
- выбор переходов;
- ожидание внешних событий;
- retry/timeout;
- историю и retention.

`task-queue-postgres` отвечает за:

- durable delivery команд исполнения;
- partition ordering по business key;
- lease/ownership worker'ов;
- delayed retry/resume commands;
- backpressure на уровне очереди.
