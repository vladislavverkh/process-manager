package dev.verkhovskiy.processmanager;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProcessDefinitionRegistryTest {

  @Test
  void rejectsConflictingPayloadTypesForSamePayloadSchemaVersion() {
    ProcessDefinition<PaymentPayloadV1> first = definition(1, 1, PaymentPayloadV1.class);
    ProcessDefinition<PaymentPayloadV2> second = definition(2, 1, PaymentPayloadV2.class);

    assertThatThrownBy(() -> new ProcessDefinitionRegistry(List.of(first, second)))
        .isInstanceOf(ProcessDefinitionException.class)
        .hasMessageContaining("Conflicting payload type for payment payload schema v1");
  }

  @Test
  void allowsSamePayloadSchemaVersionAcrossDefinitionVersionsWhenPayloadTypeMatches() {
    new ProcessDefinitionRegistry(
        List.of(
            definition(1, 1, PaymentPayloadV1.class), definition(2, 1, PaymentPayloadV1.class)));
  }

  private static <P> ProcessDefinition<P> definition(
      int definitionVersion, int payloadSchemaVersion, Class<P> payloadType) {
    return ProcessDefinition.builder("payment", payloadType)
        .version(definitionVersion)
        .payloadSchemaVersion(payloadSchemaVersion)
        .initialState("DONE")
        .terminalState("DONE", ProcessInstanceStatus.COMPLETED)
        .build();
  }

  private record PaymentPayloadV1(String paymentId) {}

  private record PaymentPayloadV2(String paymentId, String accountId) {}
}
