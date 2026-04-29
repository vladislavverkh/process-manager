# Интеграция с task-queue-postgres

`process-manager-task-queue` содержит adapter-классы, которые позволяют использовать
`task-queue-postgres` как durable executor.

Это опциональный adapter. Базовый runtime зависит только от `ProcessCommandScheduler`, поэтому
другая инфраструктура команд может подключиться своей реализацией этого интерфейса.

## Подключение

```kotlin
implementation("dev.verkhovskiy:task-queue-spring-boot-starter")
implementation("dev.verkhovskiy:process-manager-spring-boot-starter")
implementation("dev.verkhovskiy:process-manager-task-queue")
```

`process-manager-task-queue` не включается в Gradle build автоматически и не подключает соседний
`../task-queue-postgres`. Для локальной проверки adapter-модуля `task-queue-core` должен быть
доступен как обычная опубликованная зависимость, например через `mavenLocal`. После этого модуль
включается явно:

```bash
./gradlew :process-manager-task-queue:check -PprocessManager.includeTaskQueueAdapter=true
```

## Ручная конфигурация

Adapter не содержит Spring Boot autoconfiguration. Приложение, которое использует обе библиотеки,
само объявляет связь между ними:

```java
@Configuration
class ProcessManagerTaskQueueConfiguration {

  @Bean
  ProcessCommandScheduler processCommandScheduler(
      TaskProducer taskProducer, ObjectMapper objectMapper) {
    return new TaskQueueProcessCommandScheduler(taskProducer, objectMapper);
  }

  @Bean
  ProcessCommandTaskHandler processCommandTaskHandler(
      ProcessManager processManager, ObjectMapper objectMapper) {
    return new ProcessCommandTaskHandler(processManager, objectMapper);
  }
}
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

## Модель исполнения action

`process-manager-task-queue` не исполняет Java action сам по себе и не кладет action handler в
очередь. Adapter кладет в `task-queue-postgres` только технический `ProcessCommand`, а выполнение
текущего state происходит внутри `ProcessManager.resume(command)`.

Базовая цепочка:

```text
start() / signal()
  -> process-manager сохраняет durable state в pm_process_instance
  -> ProcessCommandScheduler ставит ProcessCommand в task-queue
  -> task-queue worker забирает задачу
  -> ProcessCommandTaskHandler вызывает processManager.resume(command)
  -> runtime блокирует instance, читает текущий state и вызывает нужный action bean
```

Action handler выполняется в том приложении или worker deployment, где поднят
`ProcessCommandTaskHandler` и где доступны:

- `ProcessManager`;
- все `ProcessDefinition` beans;
- все action beans, на которые ссылаются definitions;
- клиенты внешних систем, репозитории и другая бизнесовая инфраструктура, нужная action.

Очередь не сериализует Java method reference и не знает, какой action нужно вызвать. Она доставляет
только `ProcessCommand`; runtime определяет action по текущему `state` в `pm_process_instance` и
зарегистрированному `ProcessDefinition`.

## Разделение API и worker

Можно запускать одно приложение, которое и принимает HTTP/Kafka входящие события, и обрабатывает
queue tasks. Можно разделить роли:

```text
api-app
  принимает start/signal
  пишет process state
  enqueue ProcessCommand

worker-app
  читает task-queue
  вызывает processManager.resume(command)
  выполняет action handlers
```

При таком разделении `worker-app` все равно должен иметь classpath с definitions и action
implementations. Иначе он сможет прочитать `ProcessCommand`, но не сможет исполнить текущий state
процесса.

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
| `STATE_TIMEOUT` | Продолжение после истечения дедлайна текущего state, WAIT или TIMER |
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

Поток исполнения:

```text
ACTION вернул RetryableFailure
  -> runtime сохраняет retry metadata в variables_json
  -> scheduler ставит delayed ProcessCommand(RETRY, expectedVersion)
  -> worker позже вызывает resume(command)
  -> runtime повторно выполняет тот же action state
```

Если retries закончились, runtime выбирает обычный transition для последнего `RetryableFailure`.
Например process definition может перевести процесс в parked state.

## WAIT и external event

WAIT state не держит поток и не блокирует worker:

```text
WAIT state
  -> runtime регистрирует wait point в pm_process_wait
  -> instance получает статус WAITING

signal(eventType, correlationKey, payload)
  -> runtime сохраняет event в pm_process_event_inbox
  -> scheduler ставит ProcessCommand(RESUME)

worker
  -> resume(command)
  -> runtime читает событие из inbox
  -> выбирает transition по payload события
```

Если event доставлен повторно с тем же `idempotencyKey`, inbox не создаст дубликат и новая команда
не будет запланирована.

## TIMER и polling

TIMER state нужен для сценариев polling, когда внешний сервис ответил `202 Accepted`, а финальный
результат надо узнавать отдельными запросами:

```text
SEND_COMMAND
  -> WAIT_NEXT_POLL
  -> POLL_RESULT
  -> DONE / FAILED / WAIT_NEXT_POLL
```

При входе в TIMER:

```text
TIMER state
  -> runtime сохраняет state_deadline_at
  -> scheduler ставит delayed ProcessCommand(RESUME)

worker после delay
  -> resume(command)
  -> runtime переводит процесс в targetState
```

Action polling (`POLL_RESULT`) выполняется уже в следующем action state. Если сервис вернул
`PENDING`, process definition обычно переводит процесс обратно в TIMER state.

## Timeout watchdog

Timeout-команды не планируются заранее для каждого state. Runtime сохраняет `process_deadline_at` и
`state_deadline_at` в `pm_process_instance`, а `ProcessDeadlineWatchdog` сканирует уже истекшие
дедлайны батчем и ставит в очередь только фактически нужные команды.

TIMER state использует ту же `scheduleDelayed(command, partitionKey, delay)` абстракцию, что и retry:
при входе в TIMER runtime планирует delayed `RESUME`, а `state_deadline_at` остается дополнительной
защитой для watchdog.

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
- хранить или исполнять Java action handler;
- выбирать transitions;
- знать `processType`-специфичную бизнес-логику;
- быть источником истины по статусу процесса.

Все это остается в process-manager runtime и PostgreSQL state.
