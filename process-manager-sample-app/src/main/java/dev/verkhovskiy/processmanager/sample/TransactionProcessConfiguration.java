package dev.verkhovskiy.processmanager.sample;

import dev.verkhovskiy.processmanager.ProcessDefinition;
import dev.verkhovskiy.processmanager.ProcessInstanceStatus;
import dev.verkhovskiy.processmanager.RetryPolicy;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TransactionProcessConfiguration {

  static final String PROCESS_TYPE = "sample-transaction";
  static final String EVENT_POSTING_RESULT = "sample-transaction.posting-result";
  static final String EVENT_RETRY_PARKED = "sample-transaction.retry-parked";

  private static final RetryPolicy REST_RETRY =
      RetryPolicy.exponential(3, Duration.ofSeconds(1), Duration.ofSeconds(10));

  @Bean
  ProcessDefinition<TransactionPayload> sampleTransactionProcess(
      TransactionProcessingActions actions) {
    return ProcessDefinition.builder(PROCESS_TYPE, TransactionPayload.class)
        .version(1)
        .payloadSchemaVersion(1)
        .initialState("VALIDATE_TRANSACTION_TYPE")
        .processTimeout(
            timeout -> timeout.duration(Duration.ofHours(2)).targetState("TECHNICAL_FAILURE"))
        .actionState(
            "VALIDATE_TRANSACTION_TYPE",
            state ->
                state
                    .action(actions::validateTransactionType)
                    .transition(
                        transition ->
                            transition
                                .name("supported")
                                .targetState("LOOKUP_CLIENT")
                                .condition(
                                    ctx -> ctx.resultCodeEquals("TRANSACTION_TYPE_SUPPORTED")))
                    .transition(
                        transition ->
                            transition
                                .name("unsupported")
                                .targetState("BUSINESS_ERROR")
                                .condition(
                                    ctx -> ctx.resultCodeEquals("UNSUPPORTED_TRANSACTION_TYPE")))
                    .otherwise("TECHNICAL_FAILURE"))
        .actionState(
            "LOOKUP_CLIENT",
            state ->
                state
                    .action(actions::lookupClient)
                    .retry(REST_RETRY)
                    .transition(
                        transition ->
                            transition
                                .name("client-found")
                                .targetState("LOOKUP_CONTRACT")
                                .condition(ctx -> ctx.resultCodeEquals("CLIENT_FOUND")))
                    .transition(
                        transition ->
                            transition
                                .name("temporary-client-error")
                                .targetState("PARKED_TEMPORARY_FAILURE")
                                .condition(
                                    ctx ->
                                        ctx.resultCodeEquals(
                                            "CLIENT_SERVICE_TEMPORARY_UNAVAILABLE")))
                    .transition(
                        transition ->
                            transition
                                .name("client-business-error")
                                .targetState("BUSINESS_ERROR")
                                .condition(ctx -> ctx.resultCodeEquals("CLIENT_NOT_FOUND")))
                    .otherwise("TECHNICAL_FAILURE"))
        .actionState(
            "LOOKUP_CONTRACT",
            state ->
                state
                    .action(actions::lookupContract)
                    .retry(REST_RETRY)
                    .transition(
                        transition ->
                            transition
                                .name("contract-found")
                                .targetState("BUILD_TRANSACTION_ACTIONS")
                                .condition(ctx -> ctx.resultCodeEquals("CONTRACT_FOUND")))
                    .transition(
                        transition ->
                            transition
                                .name("temporary-contract-error")
                                .targetState("PARKED_TEMPORARY_FAILURE")
                                .condition(
                                    ctx ->
                                        ctx.resultCodeEquals(
                                            "CONTRACT_SERVICE_TEMPORARY_UNAVAILABLE")))
                    .transition(
                        transition ->
                            transition
                                .name("contract-business-error")
                                .targetState("BUSINESS_ERROR")
                                .condition(ctx -> ctx.resultCodeEquals("CONTRACT_NOT_FOUND")))
                    .otherwise("TECHNICAL_FAILURE"))
        .actionState(
            "BUILD_TRANSACTION_ACTIONS",
            state ->
                state
                    .action(actions::buildTransactionActions)
                    .transition(
                        transition ->
                            transition
                                .name("actions-created")
                                .targetState("PREPARE_POSTING_LAYOUT")
                                .condition(ctx -> ctx.resultCodeEquals("ACTIONS_CREATED")))
                    .otherwise("TECHNICAL_FAILURE"))
        .actionState(
            "PREPARE_POSTING_LAYOUT",
            state ->
                state
                    .action(actions::preparePostingLayout)
                    .retry(REST_RETRY)
                    .transition(
                        transition ->
                            transition
                                .name("layout-ready")
                                .targetState("SEND_POSTING_COMMAND")
                                .condition(ctx -> ctx.resultCodeEquals("POSTING_LAYOUT_READY")))
                    .transition(
                        transition ->
                            transition
                                .name("temporary-account-error")
                                .targetState("PARKED_TEMPORARY_FAILURE")
                                .condition(
                                    ctx ->
                                        ctx.resultCodeEquals(
                                            "ACCOUNT_SERVICE_TEMPORARY_UNAVAILABLE")))
                    .transition(
                        transition ->
                            transition
                                .name("layout-business-error")
                                .targetState("BUSINESS_ERROR")
                                .condition(
                                    ctx -> ctx.resultCodeEquals("POSTING_TEMPLATE_NOT_FOUND")))
                    .otherwise("TECHNICAL_FAILURE"))
        .actionState(
            "SEND_POSTING_COMMAND",
            state ->
                state
                    .action(actions::sendPostingCommand)
                    .transition(
                        transition ->
                            transition
                                .name("posting-command-sent")
                                .targetState("WAIT_POSTING_RESULT")
                                .condition(ctx -> ctx.resultCodeEquals("POSTING_COMMAND_SENT")))
                    .otherwise("TECHNICAL_FAILURE"))
        .waitState(
            "WAIT_POSTING_RESULT",
            state ->
                state
                    .eventType(EVENT_POSTING_RESULT)
                    .correlationKey(ctx -> ctx.payload().transactionId())
                    .waitTimeout(Duration.ofHours(1))
                    .transition(
                        transition ->
                            transition
                                .name("posting-created")
                                .targetState("PROCESSED")
                                .condition(ctx -> ctx.eventFieldEquals("posted", true)))
                    .transition(
                        transition ->
                            transition
                                .name("posting-rejected")
                                .targetState("BUSINESS_ERROR")
                                .condition(ctx -> ctx.eventFieldEquals("posted", false)))
                    .timeoutTransition("TECHNICAL_FAILURE")
                    .otherwise("BUSINESS_ERROR"))
        .waitState(
            "PARKED_TEMPORARY_FAILURE",
            state ->
                state
                    .eventType(EVENT_RETRY_PARKED)
                    .correlationKey(ctx -> ctx.payload().transactionId())
                    .transition(
                        transition ->
                            transition
                                .name("manual-retry")
                                .targetState("LOOKUP_CLIENT")
                                .condition(ctx -> ctx.eventFieldEquals("retry", true)))
                    .otherwise("TECHNICAL_FAILURE"))
        .terminalState("PROCESSED", ProcessInstanceStatus.COMPLETED)
        .terminalState("BUSINESS_ERROR", ProcessInstanceStatus.FAILED)
        .terminalState("TECHNICAL_FAILURE", ProcessInstanceStatus.FAILED)
        .build();
  }
}
