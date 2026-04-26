# Интеграция с task-queue-postgres

`process-manager-task-queue` использует `task-queue-postgres` как durable executor.

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
| `TIMEOUT` | Продолжение после истечения wait timeout |

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

