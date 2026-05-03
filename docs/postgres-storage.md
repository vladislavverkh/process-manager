# PostgreSQL storage

`process-manager-postgres` хранит durable state процесса. Liquibase changelog находится в:

```text
process-manager-postgres/src/main/resources/db/changelog/process-manager.postgres.sql
```

## Таблицы

### pm_process_instance

Главная таблица текущего состояния process instance.

| Колонка | Назначение |
| --- | --- |
| `instance_id` | Primary key instance |
| `process_type` | Тип сценария |
| `definition_version` | Версия graph definition |
| `payload_schema_version` | Версия JSON payload |
| `business_key` | Бизнесовый ключ процесса |
| `state` | Текущее состояние |
| `status` | `RUNNING`, `WAITING`, `COMPLETED`, `FAILED`, `CANCELLED` |
| `payload_json` | Бизнесовый payload |
| `variables_json` | Runtime variables |
| `started_at` | Время старта |
| `updated_at` | Последнее обновление |
| `process_deadline_at` | Общий дедлайн процесса |
| `state_entered_at` | Время входа в текущее state |
| `state_deadline_at` | Дедлайн текущего state, WAIT или TIMER |
| `completed_at` | Время входа в terminal status |
| `delete_after` | Момент, после которого instance можно удалить |
| `version` | Optimistic version |

Индексы:

- unique `(process_type, business_key)` для non-terminal instances;
- `(status, delete_after)` для cleanup;
- `(process_type, state, status)` для диагностики и будущего admin API.
- `(process_deadline_at, instance_id)` для watchdog общего дедлайна;
- `(state_deadline_at, instance_id)` для watchdog state/WAIT/TIMER дедлайна.

### pm_process_wait

Активные ожидания внешних событий.

| Колонка | Назначение |
| --- | --- |
| `wait_id` | Primary key wait record |
| `instance_id` | Process instance |
| `process_type` | Тип сценария |
| `state` | State, который зарегистрировал ожидание |
| `event_type` | Тип ожидаемого события |
| `correlation_key` | Ключ корреляции |
| `expires_at` | Timeout ожидания |
| `created_at` | Время регистрации wait |

Уникальность: `(event_type, correlation_key, instance_id)`.

### pm_process_event_inbox

Входящие внешние события.

| Колонка | Назначение |
| --- | --- |
| `event_id` | Primary key события |
| `event_type` | Тип события |
| `correlation_key` | Ключ корреляции |
| `idempotency_key` | Ключ идемпотентности внешней доставки |
| `payload_json` | Payload события |
| `received_at` | Время приема |
| `consumed_at` | Время обработки, пока не используется |

Уникальный индекс `(event_type, correlation_key, idempotency_key)` включается только для строк с
непустым `idempotency_key`. Это сохраняет обратную совместимость для неидемпотентного `signal(...)`.

### pm_process_history

История переходов процесса.

| Колонка | Назначение |
| --- | --- |
| `history_id` | Primary key history record |
| `instance_id` | Process instance |
| `process_type` | Тип сценария |
| `from_state` | Предыдущее состояние |
| `to_state` | Новое состояние |
| `transition_name` | Имя выбранного transition |
| `trigger_type` | `ACTION_RESULT`, `EVENT`, `TIMER`, `RETRY`, `RETRY_EXHAUSTED`, `MANUAL_CANCEL`, `MANUAL_RETRY`, ... |
| `trigger_json` | Structured trigger data |
| `created_at` | Время записи |

## Delete strategy

Сейчас FK от wait/history к instance настроены с `on delete cascade`. Cleanup terminal instances
удаляет строки из `pm_process_instance`, связанные waits/history удаляются каскадом.

В будущем при больших объемах можно перейти на явный batch-delete связанных таблиц, чтобы точнее
контролировать нагрузку и метрики.

## Clock semantics

Запросы, которые принимают решение о времени в БД, должны использовать PostgreSQL `clock_timestamp()`.
Это важно для:

- timeout wait points;
- process/state deadline watchdog;
- retention cleanup;
- delayed commands;
- корректной работы при clock skew между приложениями.

## JSONB и индексация

`payload_json` и `variables_json` хранятся как `jsonb`, но базовая модель не должна полагаться на
произвольные JSONB indexes. Для поиска и корреляции нужны отдельные явные поля:

- `business_key`;
- `event_type`;
- `correlation_key`;
- будущие materialized search keys, если появится operator UI.
