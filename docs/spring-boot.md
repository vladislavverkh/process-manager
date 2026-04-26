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
| `ProcessCommandScheduler` | Есть `TaskProducer` |
| `ProcessManager` | Есть registry, repository, scheduler и `ObjectMapper` |
| `ProcessCommandTaskHandler` | Есть `ProcessManager` |

## Свойства

Префикс:

```text
process.manager
```

| Свойство | Default | Назначение |
| --- | --- | --- |
| `process.manager.enabled` | `true` | Включает/отключает autoconfiguration |
| `process.manager.task-type` | `process-manager.command` | Зарезервировано под настройку task type |
| `process.manager.cleanup-batch-size` | `100` | Batch size будущего retention cleanup |

Важно: `task-type` уже есть в properties, но текущий `TaskQueueProcessCommandScheduler` пока использует
константу `process-manager.command`. Настройку task type нужно подключить в следующем этапе.

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
            actions::sendPayment,
            state -> state.transition("accepted", "WAIT_RESULT", ctx -> ctx.resultCodeEquals("ACCEPTED")))
        .terminalState("DONE", ProcessInstanceStatus.COMPLETED)
        .build();
  }
}
```

`ProcessDefinitionRegistry` принимает `List<ProcessDefinition<?>>` и индексирует definitions по
`processType + version`.

## Интеграция с task-queue-postgres

Приложение должно подключить `task-queue-postgres` starter и настроить его инфраструктуру. Тогда
`TaskProducer` будет доступен как bean, а process-manager сможет ставить process commands в очередь.

Минимальный набор:

```kotlin
implementation("dev.verkhovskiy:task-queue-spring-boot-starter")
implementation("dev.verkhovskiy:process-manager-spring-boot-starter")
```

Для local development этот проект использует composite build на `../task-queue-postgres`.

