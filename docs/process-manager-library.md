# Process Manager Library

Этот документ является входной точкой в документацию `process-manager`.

## Разделы

- [Архитектура](architecture.md)
- [Модель процесса и DSL](process-definition.md)
- [Runtime и состояние процесса](runtime-state.md)
- [PostgreSQL storage](postgres-storage.md)
- [Интеграция с task-queue-postgres](task-queue-integration.md)
- [Spring Boot starter](spring-boot.md)
- [Operator REST API](operator-rest-api.md)
- [Runnable Spring Boot sample](../process-manager-sample-app/README.md)
- [Roadmap](roadmap.md)

## Testkit

Модуль `process-manager-testkit` содержит assertions, deterministic runner без PostgreSQL и fake
command scheduler для unit-тестов клиентских process definitions:

```java
ProcessDefinitionAssertions.assertThat(payment)
    .isValid()
    .hasState("SEND_PAYMENT", StateKind.ACTION)
    .canReachTerminal("DONE");
```

`DeterministicProcessRunner` позволяет стартовать payload, вручную исполнять `ACTION`/`DECISION`,
доставлять event в `WAIT`, срабатывать `TIMER`/timeout и проверять state, status, history и
variables без Docker/PostgreSQL:

```java
DeterministicProcessRunner<PaymentPayload> runner =
    DeterministicProcessRunner.start(payment, "payment-1", new PaymentPayload("payment-1"));

runner.runUntilBlocked();

assertThat(runner.state()).isEqualTo("WAIT_PAYMENT_RESULT");
assertThat(runner.variables().values()).containsEntry("providerPaymentId", "provider-1");

runner.signal("payment.result", "payment-1", Map.of("approved", true));

assertThat(runner.status()).isEqualTo(ProcessInstanceStatus.COMPLETED);
assertThat(runner.history())
    .extracting(TestProcessHistoryRecord::transitionName)
    .containsExactly("accepted", "approved");
```

`FakeProcessCommandScheduler` записывает немедленные и delayed команды в память, чтобы в тестах
проверять retry/timer/deadline scheduling без реальной очереди:

```java
FakeProcessCommandScheduler scheduler = new FakeProcessCommandScheduler();

scheduler.scheduleDelayed(
    new ProcessCommand(instanceId, ProcessCommandReason.RETRY, 2),
    "payment:payment-1",
    Duration.ofSeconds(5));

assertThat(scheduler.lastCommand().delay()).isEqualTo(Duration.ofSeconds(5));
```

## Что где смотреть

- Общая декомпозиция модулей и ответственность компонентов: [architecture.md](architecture.md)
- Как описывать сценарии, шаги, условия и переходы: [process-definition.md](process-definition.md)
- Как хранится payload, variables, status, waits и history: [runtime-state.md](runtime-state.md)
- PostgreSQL-таблицы, индексы и retention cleanup: [postgres-storage.md](postgres-storage.md)
- Как `task-queue-postgres` используется для retry/resume/timeout: [task-queue-integration.md](task-queue-integration.md)
- Spring Boot autoconfiguration и свойства: [spring-boot.md](spring-boot.md)
- REST endpoints для диагностики и ручных операций: [operator-rest-api.md](operator-rest-api.md)
- Запускаемый пример приложения: [process-manager-sample-app](../process-manager-sample-app/README.md)
- Ближайшие этапы развития: [roadmap.md](roadmap.md)

## Документационный принцип

Документация должна обновляться в том же pull request/commit, где меняется публичное поведение,
runtime-семантика, схема БД, настройки или integration contract.
