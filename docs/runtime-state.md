# Runtime и состояние процесса

Process state должен быть отделен от очереди исполнения. Очередь планирует работу, а PostgreSQL
хранит актуальное состояние.

## Instance state

Минимальная runtime-модель:

```text
instance_id
process_type
definition_version
payload_schema_version
business_key
state
status
payload_json
variables_json
started_at
updated_at
completed_at
delete_after
version
```

## Payload и variables

### Payload

`payload_json` - бизнесовые данные процесса. Например:

```json
{
  "paymentId": "pay-123",
  "contractId": "contract-777",
  "amount": 150000
}
```

Payload зависит от `processType` и `payloadSchemaVersion`.

### Variables

`variables_json` - runtime-данные процесса:

- id внешней команды;
- результат предыдущего шага;
- счетчики попыток;
- промежуточные флаги;
- routing data, которую надо сохранить между resume.

Payload отвечает на вопрос "что обрабатываем". Variables отвечают на вопрос "как идет исполнение".

## Status

| Status | Значение |
| --- | --- |
| `RUNNING` | Instance исполняется или запланирован к исполнению |
| `WAITING` | Instance ожидает внешнее событие или timer |
| `COMPLETED` | Успешное terminal состояние |
| `FAILED` | Неуспешное terminal состояние |
| `CANCELLED` | Отмененное terminal состояние |

## Optimistic version

`version` нужен для защиты от устаревших process commands:

```json
{
  "instanceId": "...",
  "reason": "RESUME",
  "expectedVersion": 3
}
```

Если command пришел для старой версии instance, runtime должен пропустить его как stale.

Current implementation уже хранит `expectedVersion` в `ProcessCommand`, но full stale-command
handling еще предстоит реализовать в execution loop.

## Wait points

WAIT state создает запись:

```text
event_type
correlation_key
instance_id
expires_at
```

Когда приходит `signal(eventType, correlationKey, payload)`, runtime ищет wait points и планирует
resume для найденных instances.

## Event inbox

Входящие события сохраняются в inbox до resume:

```text
event_id
event_type
correlation_key
payload_json
received_at
consumed_at
```

Inbox нужен для audit/debug и будущей идемпотентности Kafka/Event processing.

## History

Каждый transition должен записываться в `pm_process_history`:

```text
from_state
to_state
transition_name
trigger_type
trigger_json
created_at
```

History - это audit trail процесса. Она не должна использоваться как источник текущего состояния.

## Retention

При входе в terminal state runtime должен выставить:

```text
completed_at = db_now
delete_after = db_now + retention(status)
```

Retention задается на уровне process definition:

```java
new ProcessRetention(
    Duration.ofDays(30),   // completed
    Duration.ofDays(180),  // failed
    Duration.ofDays(90))   // cancelled
```

Cleanup удаляет только terminal instances, у которых `delete_after <= db_now`.

