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

`ProcessCommandScheduler` должен быть предоставлен приложением или отдельным adapter-модулем.
Базовый starter не зависит от конкретной очереди команд.

## Свойства

Префикс:

```text
process.manager
```

| Свойство | Default | Назначение |
| --- | --- | --- |
| `process.manager.enabled` | `true` | Включает/отключает autoconfiguration |
| `process.manager.cleanup-batch-size` | `100` | Batch size будущего retention cleanup |
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

## Интеграция с task-queue-postgres

Приложение может подключить `process-manager-task-queue` и `task-queue-postgres` starter. Тогда
adapter создаст `ProcessCommandScheduler` и `ProcessCommandTaskHandler` поверх `TaskProducer`.

Минимальный набор:

```kotlin
implementation("dev.verkhovskiy:task-queue-spring-boot-starter")
implementation("dev.verkhovskiy:process-manager-spring-boot-starter")
implementation("dev.verkhovskiy:process-manager-task-queue")
```

Если нужна другая очередь, приложение может не подключать `process-manager-task-queue` и объявить
свой bean `ProcessCommandScheduler`.
