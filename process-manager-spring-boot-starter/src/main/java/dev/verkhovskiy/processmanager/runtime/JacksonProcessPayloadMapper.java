package dev.verkhovskiy.processmanager.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.verkhovskiy.processmanager.ProcessDefinition;
import dev.verkhovskiy.processmanager.ProcessPayloadMapper;
import dev.verkhovskiy.processmanager.ProcessPayloadMappingException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import lombok.RequiredArgsConstructor;

/** Jackson-based payload mapper for the stored JSON process payload. */
@RequiredArgsConstructor
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "ObjectMapper is an injected infrastructure bean.")
public class JacksonProcessPayloadMapper implements ProcessPayloadMapper {

  private final ObjectMapper objectMapper;

  @Override
  public String serialize(ProcessDefinition<?> definition, Object payload) {
    if (payload == null) {
      throw new ProcessPayloadMappingException(
          "Process payload must not be null: " + processLabel(definition));
    }
    Object typedPayload;
    try {
      typedPayload = objectMapper.convertValue(payload, definition.payloadType());
    } catch (IllegalArgumentException e) {
      throw new ProcessPayloadMappingException(
          "Cannot map process payload to "
              + definition.payloadType().getName()
              + ": "
              + processLabel(definition),
          e);
    }
    if (typedPayload == null) {
      throw new ProcessPayloadMappingException(
          "Process payload mapper returned null: " + processLabel(definition));
    }
    try {
      return objectMapper.writeValueAsString(typedPayload);
    } catch (JsonProcessingException e) {
      throw new ProcessPayloadMappingException(
          "Cannot serialize process payload: " + processLabel(definition), e);
    }
  }

  @Override
  public <P> P deserialize(
      ProcessDefinition<P> definition, int payloadSchemaVersion, String payloadJson) {
    if (payloadSchemaVersion != definition.payloadSchemaVersion()) {
      throw new ProcessPayloadMappingException(
          "Unsupported process payload schema: "
              + processLabel(definition)
              + ", storedPayloadSchemaVersion="
              + payloadSchemaVersion);
    }
    if (payloadJson == null || payloadJson.isBlank()) {
      throw new ProcessPayloadMappingException(
          "Stored process payload JSON must not be blank: " + processLabel(definition));
    }
    try {
      P payload = objectMapper.readValue(payloadJson, definition.payloadType());
      if (payload == null) {
        throw new ProcessPayloadMappingException(
            "Stored process payload JSON resolved to null: " + processLabel(definition));
      }
      return payload;
    } catch (IOException e) {
      throw new ProcessPayloadMappingException(
          "Cannot deserialize process payload: "
              + processLabel(definition)
              + ", storedPayloadSchemaVersion="
              + payloadSchemaVersion,
          e);
    }
  }

  private static String processLabel(ProcessDefinition<?> definition) {
    return "processType="
        + definition.processType()
        + ", definitionVersion="
        + definition.version()
        + ", payloadSchemaVersion="
        + definition.payloadSchemaVersion();
  }
}
