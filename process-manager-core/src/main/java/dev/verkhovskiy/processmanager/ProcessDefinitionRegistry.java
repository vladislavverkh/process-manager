package dev.verkhovskiy.processmanager;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Реестр версионированных описаний процессов. */
public class ProcessDefinitionRegistry {

  private final Map<DefinitionKey, ProcessDefinition<?>> definitions = new ConcurrentHashMap<>();
  private final Map<PayloadKey, Class<?>> payloadTypes = new ConcurrentHashMap<>();

  public ProcessDefinitionRegistry(Collection<ProcessDefinition<?>> definitions) {
    definitions.forEach(this::register);
  }

  public void register(ProcessDefinition<?> definition) {
    ProcessDefinitionValidator.validateOrThrow(definition);
    DefinitionKey key = new DefinitionKey(definition.processType(), definition.version());
    if (definitions.putIfAbsent(key, definition) != null) {
      throw new ProcessDefinitionException(
          "Duplicate process definition: "
              + definition.processType()
              + " v"
              + definition.version());
    }
    PayloadKey payloadKey =
        new PayloadKey(definition.processType(), definition.payloadSchemaVersion());
    Class<?> previousPayloadType = payloadTypes.putIfAbsent(payloadKey, definition.payloadType());
    if (previousPayloadType != null && !previousPayloadType.equals(definition.payloadType())) {
      definitions.remove(key, definition);
      throw new ProcessDefinitionException(
          "Conflicting payload type for "
              + definition.processType()
              + " payload schema v"
              + definition.payloadSchemaVersion()
              + ": "
              + previousPayloadType.getName()
              + " vs "
              + definition.payloadType().getName());
    }
  }

  public ProcessDefinition<?> latest(String processType) {
    return definitions.entrySet().stream()
        .filter(entry -> entry.getKey().processType().equals(processType))
        .max(Comparator.comparingInt(entry -> entry.getKey().version()))
        .map(Map.Entry::getValue)
        .orElseThrow(() -> new ProcessDefinitionException("Unknown process type: " + processType));
  }

  public ProcessDefinition<?> get(String processType, int version) {
    ProcessDefinition<?> definition = definitions.get(new DefinitionKey(processType, version));
    if (definition == null) {
      throw new ProcessDefinitionException(
          "Unknown process definition: " + processType + " v" + version);
    }
    return definition;
  }

  private record DefinitionKey(String processType, int version) {}

  private record PayloadKey(String processType, int payloadSchemaVersion) {}
}
