# Spring Boot starter

Модуль `process-manager-spring-boot-starter` предоставляет autoconfiguration для runtime-компонентов.

## AutoConfiguration

Файл регистрации:

```text
process-manager-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

Класс:

```java
ProcessManagerAutoConfiguration
```

## Создаваемые beans

| Bean | Условие |
| --- | --- |
| `ProcessDefinitionRegistry` | Всегда, если включен starter |
| `PostgresProcessRepository` | Есть `NamedParameterJdbcTemplate` |
| `ProcessInspector` | Есть `PostgresProcessRepository` и `ObjectMapper` |
| `ProcessManager` | Есть registry, repository, scheduler и `ObjectMapper` |
| `ProcessOperator` | Есть registry, repository, scheduler и `ObjectMapper` |
| `ProcessDeadlineWatchdog` | Есть repository и scheduler |
| `ProcessRetentionCleanup` | Есть `PostgresProcessRepository` |
| `ProcessManagerMetrics` | Есть Micrometer `MeterRegistry` |
| PostgreSQL-backed metrics gauges | Есть Micrometer `MeterRegistry` и `PostgresProcessRepository` |

`ProcessCommandScheduler` должен быть предоставлен приложением или отдельным adapter-модулем.
Базовый starter не зависит от конкретной очереди команд.

Если Micrometer отсутствует, runtime использует no-op recorder и продолжает работать без metrics
backend.

## Свойства

Префикс:

```text
process.manager
```

| Свойство | Default | Назначение |
| --- | --- | --- |
| `process.manager.enabled` | `true` | Включает/отключает autoconfiguration |
| `process.manager.cleanup-batch-size` | `100` | Batch size одного прохода retention cleanup |
| `process.manager.deadline-batch-size` | `100` | Batch size одного прохода deadline watchdog |

## Регистрация process definitions

Сценарии регистрируются как Spring beans:

```java
@Configuration(proxyBeanMethods = false)
class PaymentProcessConfiguration {

  @Bean
  ProcessDefinition<PaymentPayload> paymentProcessDefinition(PaymentActions actions) {
    return ProcessDefinition.builder("payment", PaymentPayload.class)
        .version(1)
        .payloadSchemaVersion(1)
        .initialState("SEND_PAYMENT")
        .actionState(
            "SEND_PAYMENT",
            state ->
                state
                    .action(actions::sendPayment)
                    .transition(
                        transition ->
                            transition
                                .name("accepted")
                                .targetState("WAIT_RESULT")
                                .condition(ctx -> ctx.resultCodeEquals("ACCEPTED"))))
        .terminalState("DONE", ProcessInstanceStatus.COMPLETED)
        .build();
  }
}
```

`ProcessDefinitionRegistry` принимает `List<ProcessDefinition<?>>` и индексирует definitions по
`processType + version`.

## Диагностика процессов

Starter создает bean `ProcessInspector`. Он читает состояние без блокировок исполнения и не меняет
runtime-данные:

```java
Optional<ProcessDetailsView> details = processInspector.findDetails(instanceId);

List<ProcessInstanceView> waitingPayments =
    processInspector.findInstances(
        ProcessInstanceQuery.builder()
            .processType("payment")
            .status(ProcessInstanceStatus.WAITING)
            .limit(100)
            .build());
```

`ProcessDetailsView` содержит текущий instance, зарегистрированные wait points и историю переходов.

## Ручные операции

Starter создает bean `ProcessOperator` для операторских действий:

```java
processOperator.cancel(instanceId, "customer request");
processOperator.scheduleResume(instanceId);
processOperator.scheduleRetry(instanceId);
```

`cancel(...)` переводит только активный процесс в `CANCELLED`, удаляет wait points и пишет history
с trigger type `MANUAL_CANCEL`. `scheduleResume(...)` и `scheduleRetry(...)` ставят command в очередь
с текущей `version`, поэтому команда станет stale, если процесс успеет измениться раньше.

## Deadline watchdog

Starter создает bean `ProcessDeadlineWatchdog`. Приложение само задает расписание его запуска,
например через Spring Scheduling:

```java
@Scheduled(fixedDelayString = "PT10S")
void processDeadlines() {
  processDeadlineWatchdog.runOnce();
}
```

## Retention cleanup

Starter создает bean `ProcessRetentionCleanup`. Он удаляет terminal instances, у которых
`delete_after <= clock_timestamp()` в PostgreSQL:

```java
@Scheduled(fixedDelayString = "PT1M")
void cleanupRetainedProcesses() {
  processRetentionCleanup.runOnce();
}
```

Размер батча задается через `process.manager.cleanup-batch-size`.

## Metrics

При наличии Micrometer `MeterRegistry` starter публикует runtime metrics:

- process lifecycle: `process.manager.instances.started`, `process.manager.instances.terminal`,
  `process.manager.process.duration`;
- command execution: `process.manager.commands.resumed`, `process.manager.resume.duration`,
  `process.manager.execution.steps`, `process.manager.execution.max.steps.exceeded`;
- state machine flow: `process.manager.action.duration`, `process.manager.transitions`,
  `process.manager.state.duration`, `process.manager.optimistic.lock.conflicts`;
- wait/event/timer/retry: `process.manager.waits.registered`, `process.manager.events.received`,
  `process.manager.events.consumed`, `process.manager.timers.scheduled`,
  `process.manager.timers.fired`, `process.manager.retries.scheduled`;
- deadline/operator: `process.manager.deadline.scan.duration`,
  `process.manager.deadline.commands.scheduled`, `process.manager.retention.cleanup.runs`,
  `process.manager.retention.cleanup.deleted`, `process.manager.retention.cleanup.duration`,
  `process.manager.operator.operations`.

Starter также регистрирует PostgreSQL-backed gauges:

- `process.manager.instances.active`;
- `process.manager.waits.active`;
- `process.manager.events.unconsumed`;
- `process.manager.deadline.overdue`.
- `process.manager.retention.expired`.

Для Prometheus endpoint приложение должно подключить Actuator и Prometheus registry:

```kotlin
implementation("org.springframework.boot:spring-boot-starter-actuator")
runtimeOnly("io.micrometer:micrometer-registry-prometheus")
```

Минимальная настройка exposure:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
```

В sample app эти зависимости, endpoint и Grafana dashboard уже настроены.

## Интеграция с task-queue-postgres

`process-manager-spring-boot-starter` не связывает runtime с конкретной очередью. Приложение должно
само объявить `ProcessCommandScheduler`. Если используется `task-queue-postgres`, можно подключить
adapter-классы и зарегистрировать их вручную.

Минимальный набор:

```kotlin
implementation("dev.verkhovskiy:task-queue-spring-boot-starter")
implementation("dev.verkhovskiy:process-manager-spring-boot-starter")
implementation("dev.verkhovskiy:process-manager-task-queue")
```

Пример конфигурации:

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

Task queue хранит только `ProcessCommand`; сами action handlers выполняются в приложении/worker при
вызове `processManager.resume(command)`. Worker deployment должен иметь те же process definitions и
action beans, что нужны для исполнения процесса. Подробная модель описана в
[task-queue-integration.md](task-queue-integration.md).

Если нужна другая очередь, приложение может не подключать `process-manager-task-queue` и объявить
свой bean `ProcessCommandScheduler`.
