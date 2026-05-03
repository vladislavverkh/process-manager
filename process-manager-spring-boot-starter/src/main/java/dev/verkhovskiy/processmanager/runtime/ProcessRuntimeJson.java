package dev.verkhovskiy.processmanager.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;

final class ProcessRuntimeJson {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final ObjectMapper objectMapper;

  ProcessRuntimeJson(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  Map<String, Object> readMap(String json, String valueName) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, MAP_TYPE);
    } catch (IOException e) {
      throw new IllegalArgumentException("Cannot deserialize " + valueName, e);
    }
  }

  String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value == null ? Map.of() : value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Cannot serialize process payload", e);
    }
  }
}
