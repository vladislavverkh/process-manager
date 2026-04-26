# Интеграция с task-queue-postgres

`process-manager-task-queue` использует `task-queue-postgres` как durable executor.

Это опциональный adapter. Базовый runtime зависит только от `ProcessCommandScheduler`, поэтому
другая инфраструктура команд может подключиться своей реализацией этого интерфейса.

## Подключение

```kotlin
implementation("dev.verkhovskiy:task-queue-spring-boot-starter")
implementation("dev.verkhovskiy:process-manager-spring-boot-starter")
implementation("dev.verkhovskiy:process-manager-task-queue")
```

Для локальной разработки модуль `process-manager-task-queue` включается в Gradle build, если рядом
есть `../task-queue-postgres`. Явное управление:

```bash
./gradlew check -PprocessManager.includeTaskQueueAdapter=false
./gradlew check -PprocessManager.includeTaskQueueAdapter=true
```

## Autoconfiguration

Adapter создает:

- `ProcessCommandScheduler`, если есть `TaskProducer`;
- `ProcessCommandTaskHandler`, если есть `ProcessManager`.

Отключение:

```properties
process.manager.task-queue.enabled=false
```

## Task type

По умолчанию используется task type:

```text
process-manager.command
```

Handler:

```java
ProcessCommandTaskHandler
```

Scheduler:

```java
TaskQueueProcessCommandScheduler
```

## Command payload

Queue payload содержит только technical command:

```json
{
  "instanceId": "018f0000-0000-7000-8000-000000000001",
  "reason": "RESUME",
  "expectedVersion": 3
}
```

`reason`:

| Reason | Назначение |
| --- | --- |
| `START` | Первое исполнение после создания instance |
| `RESUME` | Возобновление после external event |
| `RETRY` | Повтор action после retryable failure |
| `PROCESS_TIMEOUT` | Продолжение после истечения общего дедлайна процесса |
| `STATE_TIMEOUT` | Продолжение после истечения дедлайна текущего state или WAIT |
| `TIMEOUT` | Legacy alias для `STATE_TIMEOUT` |

## Partition key

Команды должны ставиться с partition key:

```text
processType:businessKey
```

Это дает последовательную обработку команд одного бизнес-объекта. Например:

```text
payment:pay-123
contract-closing:contract-777
```

Current implementation для `start()` использует `processType:businessKey`, а для `signal()` пока
использует `processType:instanceId`. Это временное ограничение MVP, потому что wait snapshot сейчас
не содержит `businessKey`.

## Delayed retry

Для retry runtime должен вызывать:

```java
ProcessCommandScheduler.scheduleDelayed(command, partitionKey, delay)
```

Task queue считает delay от времени PostgreSQL через `enqueueDelayed`, что защищает от JVM clock skew.

## Timeout watchdog

Timeout-команды не планируются заранее для каждого state. Runtime сохраняет `process_deadline_at` и
`state_deadline_at` в `pm_process_instance`, а `ProcessDeadlineWatchdog` сканирует уже истекшие
дедлайны батчем и ставит в очередь только фактически нужные команды.

Типичный запуск в приложении:

```java
@Scheduled(fixedDelayString = "PT10S")
void processDeadlines() {
  processDeadlineWatchdog.runOnce();
}
```

## Stale commands

`expectedVersion` должен использоваться execution loop:

- если `expectedVersion >= 0` и instance version отличается, command считается stale;
- stale command не должен менять process state;
- stale command можно логировать и метрифицировать.

Это нужно, потому что retry/resume/timeout commands могут прийти позже, чем процесс уже перешел в
другое состояние.

## Что не должен делать task queue

Task queue не должен:

- хранить business payload процесса;
- выбирать transitions;
- знать `processType`-специфичную бизнес-логику;
- быть источником истины по статусу процесса.

Все это остается в process-manager runtime и PostgreSQL state.
