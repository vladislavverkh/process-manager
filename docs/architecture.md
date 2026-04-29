# Архитектура

`process-manager` разделяет описание бизнес-сценариев, durable state и асинхронное исполнение.

Главный принцип: PostgreSQL хранит единственное актуальное состояние процесса, а исполнитель команд
хранит только намерение продолжить процесс.

## Модули

| Модуль | Ответственность |
| --- | --- |
| `process-manager-core` | Process definition, state model, conditional transitions, retry/retention contracts |
| `process-manager-postgres` | Таблицы и repository для process instances, waits, event inbox и history |
| `process-manager-task-queue` | Опциональный adapter-код для приложений, которые явно связывают process-manager и `task-queue-postgres` |
| `process-manager-spring-boot-starter` | Autoconfiguration runtime-компонентов |
| `process-manager-rest` | REST endpoints для диагностики и ручных операторских действий |
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
  | application-provided durable command delivery
  v
task-queue-postgres / Kafka / another command executor

ProcessDeadlineWatchdog periodically scans `pm_process_instance` for expired
`process_deadline_at`/`state_deadline_at` rows and schedules timeout commands only for rows that are
already overdue.
```

## Runtime flow

### Start

1. Caller invokes `ProcessManager.start(processType, businessKey, payload)`.
2. Runtime finds latest `ProcessDefinition` for `processType`.
3. Runtime creates `pm_process_instance` with initial state, если активный instance с таким
   `processType + businessKey` еще не существует.
4. Runtime schedules `ProcessCommand(reason=START)` through `ProcessCommandScheduler`.
5. Command executor later executes the command and resumes the instance.

Повторный `start` для уже активного instance возвращает существующий `instance_id` и не планирует
дополнительный `START`.

### External signal

1. Caller invokes `ProcessManager.signal(eventType, correlationKey, payload)` или overload с
   `idempotencyKey`.
2. Runtime stores the event in `pm_process_event_inbox`.
3. Runtime finds matching rows in `pm_process_wait`.
4. Runtime schedules resume commands for matched instances.

Если передан `idempotencyKey`, повторная доставка с тем же `eventType + correlationKey +
idempotencyKey` не создает новую inbox-запись и не планирует повторный resume.

### Resume

1. Command executor receives serialized or native `ProcessCommand`.
2. It calls `ProcessManager.resume(command)`.
4. Runtime locks the instance, checks command version, evaluates current state and executes the next
   transition.

Current implementation handles ACTION, WAIT, TIMER, DECISION and TERMINAL states in the PostgreSQL-backed
runtime. Retry routing exists for retryable failures; timeout routing is driven by stored deadlines
and `ProcessDeadlineWatchdog`.

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

Adapter-классы к `task-queue-postgres` отвечают за:

- постановку `ProcessCommand` в `task-queue-postgres`;
- единый task type для команд process-manager;
- обработчик queue task, который вызывает `ProcessManager.resume(command)`.

Другие реализации могут подключиться через свой `ProcessCommandScheduler` и свой command handler,
который вызывает `ProcessManager.resume(command)`.
