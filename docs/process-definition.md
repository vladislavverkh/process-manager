# Модель процесса и DSL

Process definition описывает бизнес-сценарий как набор состояний и условных переходов.

## Базовые понятия

| Понятие | Описание |
| --- | --- |
| `processType` | Тип сценария: `payment`, `contract-closing`, `refund` |
| `definitionVersion` | Версия графа процесса |
| `payloadSchemaVersion` | Версия структуры бизнесового payload |
| `state` | Текущее состояние instance |
| `transition` | Именованный условный переход в другое состояние |
| `payload` | Бизнесовые данные сценария |
| `variables` | Runtime-переменные процесса |
| `processTimeout` | Общий дедлайн исполнения process instance |
| `stateTimeout` | Дедлайн нахождения instance в конкретном state |

`definitionVersion` и `payloadSchemaVersion` намеренно разделены. Можно изменить граф процесса без
изменения payload и наоборот.

## Типы состояний

### ACTION

Выполняет бизнес-действие: синхронный вызов другого сервиса, проверку, отправку команды.

Action возвращает `StepResult`, после чего runtime выбирает исходящий transition по условиям.

### WAIT

Регистрирует ожидание внешнего события:

- `eventType`
- `correlationKey`
- optional timeout

Когда приходит `signal(eventType, correlationKey, payload)`, runtime возобновляет процесс и выбирает
transition по payload события.

`waitTimeout` сохраняется как `state_deadline_at` и обрабатывается watchdog'ом. Runtime не создает
отложенную команду для каждого WAIT заранее.

### DECISION

Не вызывает внешние системы. Только выбирает переход по текущим `payload`, `variables`, результатам
предыдущих шагов или другим данным контекста.

### TERMINAL

Финальное состояние. При входе в него runtime должен выставить terminal `status` и `delete_after`
по retention policy.

## Условные переходы

Переход задается как:

```text
transition name
target state
priority
condition
```

Правила выбора:

1. Runtime проверяет все transitions текущего state.
2. Подходящие transitions сортируются по `priority`.
3. Выбирается transition с минимальным priority.
4. Если нет совпадений, это ошибка definition/runtime, если не задан `otherwise`.
5. Если несколько transitions совпали с одинаковым priority, это ambiguity error.
6. Conditions должны быть deterministic и без side effects.
7. Внешние вызовы разрешены только в actions, не в conditions.

`otherwise(targetState)` создает fallback transition с низшим приоритетом.

## Timeout policies

Есть два уровня таймаутов:

- `processTimeout(timeout, targetState)` - общий дедлайн instance с момента старта процесса;
- `state.timeout(timeout, targetState)` - дедлайн нахождения в конкретном state;
- `state.timeoutTransition(targetState)` - target для timeout у WAIT state, где duration уже задан в
  `waitState(..., timeout, ...)`.

Когда дедлайн истекает, watchdog планирует `PROCESS_TIMEOUT` или `STATE_TIMEOUT` command. Runtime
повторно блокирует instance, проверяет актуальную `version` и переводит процесс только если дедлайн
все еще актуален.

Java не поддерживает named arguments, поэтому DSL поддерживает readable builder-style overloads:

- `processTimeout(timeout -> timeout.duration(...).targetState(...))`;
- `actionState("STATE", state -> state.action(...).transition(...))`;
- `waitState("STATE", state -> state.eventType(...).correlationKey(...).waitTimeout(...))`;
- `transition(t -> t.name(...).targetState(...).condition(...))`.

Позиционные overloads остаются для обратной совместимости, но для новых definitions лучше
использовать builder-style.

## Валидация definition

При `build()` и регистрации в `ProcessDefinitionRegistry` выполняется структурная проверка графа:

- у `ACTION` должен быть action handler;
- у `WAIT` должны быть `eventType` и `correlationKey`;
- у `TERMINAL` должен быть terminal status и не должно быть transitions;
- у non-terminal states должны быть исходящие transitions;
- transition и timeout targets должны указывать на существующие states;
- priorities transitions внутри одного state не должны повторяться;
- states должны быть достижимы из `initialState`;
- из каждого достижимого non-terminal state должен существовать путь к terminal state.

Для тестов process definitions доступен `process-manager-testkit`:

```java
ProcessDefinitionAssertions.assertThat(payment)
    .isValid()
    .hasNoUnreachableStates()
    .canReachTerminal("DONE");
```

## Пример definition

```java
record PaymentPayload(String paymentId, long amount) {}

ProcessDefinition<PaymentPayload> payment =
    ProcessDefinition.builder("payment", PaymentPayload.class)
        .version(1)
        .payloadSchemaVersion(1)
        .initialState("SEND_PAYMENT")
        .processTimeout(
            timeout -> timeout.duration(Duration.ofHours(2)).targetState("TECHNICAL_FAILURE"))
        .actionState(
            "SEND_PAYMENT",
            state ->
                state
                    .action(ctx -> sendPayment(ctx.payload()))
                    .retry(RetryPolicy.exponential(3, Duration.ofSeconds(1), Duration.ofMinutes(1)))
                    .transition(
                        transition ->
                            transition
                                .name("completed-sync")
                                .targetState("DONE")
                                .condition(ctx -> ctx.resultCodeEquals("COMPLETED")))
                    .transition(
                        transition ->
                            transition
                                .name("accepted-async")
                                .targetState("WAIT_PAYMENT_RESULT")
                                .condition(ctx -> ctx.resultCodeEquals("ACCEPTED")))
                    .transition(
                        transition ->
                            transition
                                .name("rejected")
                                .targetState("FAILED")
                                .condition(ctx -> ctx.resultCodeEquals("REJECTED")))
                    .otherwise("TECHNICAL_FAILURE"))
        .waitState(
            "WAIT_PAYMENT_RESULT",
            state ->
                state
                    .eventType("payment.result")
                    .correlationKey(ctx -> ctx.payload().paymentId())
                    .waitTimeout(Duration.ofHours(1))
                    .transition(
                        transition ->
                            transition
                                .name("approved")
                                .targetState("DONE")
                                .condition(ctx -> ctx.eventFieldEquals("status", "APPROVED")))
                    .transition(
                        transition ->
                            transition
                                .name("declined")
                                .targetState("FAILED")
                                .condition(ctx -> ctx.eventFieldEquals("status", "DECLINED")))
                    .timeoutTransition("TECHNICAL_FAILURE")
                    .otherwise("TECHNICAL_FAILURE"))
        .terminalState("DONE", ProcessInstanceStatus.COMPLETED)
        .terminalState("FAILED", ProcessInstanceStatus.FAILED)
        .terminalState("TECHNICAL_FAILURE", ProcessInstanceStatus.FAILED)
        .build();
```

## StepResult

Action возвращает один из вариантов:

| Result | Назначение |
| --- | --- |
| `Success(code, data)` | Успешный бизнес-результат |
| `BusinessFailure(code, data)` | Контролируемый бизнес-отказ |
| `RetryableFailure(code, message)` | Техническая ошибка, которую можно повторить |
| `FatalFailure(code, message)` | Техническая ошибка без retry |
| `AwaitEvent(eventType, correlationKey, timeout)` | Action просит runtime перейти в ожидание события |

`StepResult` не должен содержать тяжелый payload процесса. Большие данные должны храниться в
`payload`/`variables` или внешней системе, а result должен содержать routing data.

`Success.data` и `BusinessFailure.data` runtime сохраняет в `variables_json`, чтобы следующие
states могли принимать решения по данным предыдущего action. Если action должен явно сохранить
дополнительные runtime-переменные, можно использовать:

```java
return StepResult.success("ACCEPTED", Map.of("providerPaymentId", "provider-123"))
    .withVariable("manualReview", true)
    .withVariables(Map.of("riskScore", 72));
```

Runtime также сохраняет служебные variables:

- `_pm.lastActionResult` - последний action result;
- `_pm.lastEvent` - последнее внешнее событие, если процесс продолжился из WAIT;
- `_pm.lastRetry` - metadata последнего запланированного retry;
- `_pm.lastTrigger` - последняя причина продолжения процесса.

## Registry

В Spring Boot сценарии будут регистрироваться как beans:

```java
@Bean
ProcessDefinition<PaymentPayload> paymentProcess() {
  return payment;
}
```

`ProcessDefinitionRegistry` хранит несколько версий одного `processType`. Новый instance стартует на
latest version, а уже созданный instance продолжает работать на своей `definitionVersion`.
