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
process_deadline_at
state_entered_at
state_deadline_at
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

Runtime обновляет variables после исполнения steps:

- `Success.data` и `BusinessFailure.data` из `StepResult` merge'ятся в верхний уровень
  `variables_json`;
- `StepResult.withVariable(...)` и `StepResult.withVariables(...)` сохраняют явные изменения
  variables;
- `_pm.lastActionResult` хранит последний результат action;
- `_pm.lastEvent` хранит последнее внешнее событие, которое возобновило WAIT;
- `_pm.lastRetry` хранит metadata последнего retry или retry exhaustion;
- `_pm.lastTrigger` хранит последнюю причину продолжения процесса: `ACTION_RESULT`, `EVENT`,
  `TIMER`, `PROCESS_TIMEOUT`, `STATE_TIMEOUT`, `RETRY`, `RETRY_EXHAUSTED`, `MANUAL_CANCEL`,
  `MANUAL_RETRY` или `START`.

Служебный префикс `_pm.` зарезервирован runtime'ом. Пользовательские variables не должны
использовать этот namespace.

## Status

| Status | Значение |
| --- | --- |
| `RUNNING` | Instance исполняется или запланирован к исполнению |
| `WAITING` | Instance ожидает внешнее событие или TIMER |
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

Current implementation пропускает команды с несовпадающим `expectedVersion`. Метрики и более
детальная политика stale commands еще предстоят.

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

Повторный `start(processType, businessKey, payload)` для уже активного instance не создает новый
процесс, а возвращает существующий `instance_id`. Активными считаются instances в статусах
`RUNNING` и `WAITING`; после terminal статуса тот же `businessKey` можно использовать снова.

## Deadlines

Runtime хранит дедлайны в `pm_process_instance`:

- `process_deadline_at` - общий дедлайн процесса, считается от `started_at`;
- `state_entered_at` - момент входа в текущее state;
- `state_deadline_at` - дедлайн текущего state, если он задан.

WAIT timeout и TIMER delay также попадают в `state_deadline_at`. Это значит, что для 10 000
процессов из 10 шагов не создаются 100 000 отложенных timeout-команд. Отдельный watchdog
периодически выбирает только уже истекшие дедлайны через `for update skip locked` и планирует
`PROCESS_TIMEOUT` или `STATE_TIMEOUT` command с текущей `version`. При входе в TIMER runtime также
планирует delayed `RESUME`, чтобы polling-сценарии продолжались без внешнего события.

Если процесс успел перейти дальше или завершиться, command становится stale и не меняет состояние.

Дедлайн не прерывает уже выполняющийся Java `ACTION` внутри потока. Внешние вызовы внутри action
должны иметь собственные client timeouts; отдельный шаг по выносу action execution из транзакции или
в worker lease нужен, если потребуется принудительно обнаруживать именно зависший поток исполнения.

## Event inbox

Входящие события сохраняются в inbox до resume:

```text
event_id
event_type
correlation_key
idempotency_key
payload_json
received_at
consumed_at
```

Inbox нужен для audit/debug и идемпотентной обработки внешней доставки.

Для идемпотентной доставки внешних событий нужно использовать overload:

```java
processManager.signal(eventType, correlationKey, idempotencyKey, payload);
```

Повторное событие с тем же `eventType + correlationKey + idempotencyKey` не вставляется в inbox и
не планирует повторные resume commands. Старый overload без `idempotencyKey` остается
неидемпотентным.

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

## Inspection API

Для операционной диагностики runtime предоставляет `ProcessInspector`:

- `findInstance(instanceId)` - текущий snapshot процесса;
- `findActiveInstance(processType, businessKey)` - активный instance для business key;
- `findInstances(query)` - список процессов по фильтрам;
- `findWaits(instanceId)` - wait points процесса;
- `findHistory(instanceId)` - история переходов;
- `findDetails(instanceId)` - instance, waits и history одним объектом.

Фильтр `ProcessInstanceQuery` поддерживает `processType`, `businessKey`, `state`, набор `statuses`,
дедлайн не позднее указанного момента и `limit`. Эти запросы предназначены для чтения и не должны
использоваться как часть логики выбора transitions.

## Operator API

`ProcessOperator` выполняет ручные операционные действия:

- `cancel(instanceId, reason)` - переводит активный процесс в `CANCELLED`;
- `scheduleResume(instanceId)` - планирует ручной `RESUME` с текущей `version`;
- `scheduleRetry(instanceId)` - сбрасывает retry-счетчики текущего state и планирует ручной
  `RETRY` для процесса в статусе `RUNNING`.

Отмена удаляет wait points, записывает history с `trigger_type = MANUAL_CANCEL`, сохраняет
`_pm.lastCancel` и обновляет `_pm.lastTrigger`. Старые versioned commands станут stale после
инкремента `version`, а unversioned resume commands игнорируются для terminal instances.
Ручной retry удаляет `_pm.retry.<state>.attempt`, `_pm.retry.<state>` и `_pm.lastRetry`, сохраняет
`_pm.lastTrigger.type = MANUAL_RETRY`, пишет history `manual-retry` и инкрементит `version`.
Это позволяет оператору повторно запустить state после исчерпания автоматического retry budget, а
старые delayed retry commands станут stale.

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
