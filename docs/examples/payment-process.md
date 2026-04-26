# Пример платежного процесса

Этот пример показывает текущий стиль описания сценария в Java DSL.

## Сценарий

Платеж может завершиться синхронно, уйти в асинхронное ожидание результата или быть отклонен.

```text
SEND_PAYMENT
  -> DONE                  when action result COMPLETED
  -> WAIT_PAYMENT_RESULT   when action result ACCEPTED
  -> FAILED                when action result REJECTED
  -> TECHNICAL_FAILURE     otherwise

WAIT_PAYMENT_RESULT
  -> DONE                  when event.status == APPROVED
  -> FAILED                when event.status == DECLINED
  -> TECHNICAL_FAILURE     otherwise
```

## Payload

```java
record PaymentPayload(String paymentId, String contractId, long amount) {}
```

## Action

```java
class PaymentActions {

  StepResult sendPayment(ProcessContext<PaymentPayload> ctx) {
    PaymentPayload payload = ctx.payload();

    // Внешний синхронный вызов payment-service.
    PaymentResponse response = paymentClient.send(payload.paymentId(), payload.amount());

    return switch (response.status()) {
      case COMPLETED -> StepResult.success("COMPLETED");
      case ACCEPTED -> StepResult.success("ACCEPTED", Map.of("externalCommandId", response.commandId()));
      case REJECTED -> StepResult.businessFailure("REJECTED", Map.of("reason", response.reason()));
      case TEMPORARY_ERROR -> StepResult.retryableFailure("PAYMENT_SERVICE_UNAVAILABLE", response.message());
    };
  }
}
```

## Definition

```java
@Bean
ProcessDefinition<PaymentPayload> paymentProcess(PaymentActions actions) {
  return ProcessDefinition.builder("payment", PaymentPayload.class)
      .version(1)
      .payloadSchemaVersion(1)
      .initialState("SEND_PAYMENT")
      .processTimeout(
          timeout -> timeout.duration(Duration.ofHours(2)).targetState("TECHNICAL_FAILURE"))
      .retention(
          new ProcessRetention(
              Duration.ofDays(30),
              Duration.ofDays(180),
              Duration.ofDays(90)))
      .actionState(
          "SEND_PAYMENT",
          state ->
              state
                  .action(actions::sendPayment)
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
}
```

## Start

```java
UUID instanceId =
    processManager.start(
        "payment",
        paymentPayload.paymentId(),
        paymentPayload);
```

## Signal из Kafka

Kafka listener должен преобразовать сообщение в process signal:

```java
processManager.signal(
    "payment.result",
    event.paymentId(),
    event.eventId(),
    Map.of(
        "status", event.status(),
        "providerOperationId", event.providerOperationId()));
```

После signal runtime сохраняет событие в inbox, находит wait point по
`event_type + correlation_key` и ставит resume command в task queue. `event.eventId()` используется
как idempotency key, поэтому повторная доставка того же события не создаст повторное inbox-событие.
