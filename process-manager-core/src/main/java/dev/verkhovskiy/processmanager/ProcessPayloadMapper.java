package dev.verkhovskiy.processmanager;

/** Maps process payloads between Java objects and persisted JSON payload schema versions. */
public interface ProcessPayloadMapper {

  /** Serializes and validates a new process payload before it is stored. */
  String serialize(ProcessDefinition<?> definition, Object payload);

  /** Deserializes a stored process payload for execution of the matching process definition. */
  <P> P deserialize(ProcessDefinition<P> definition, int payloadSchemaVersion, String payloadJson);
}
