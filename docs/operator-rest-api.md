# Operator REST API

Модуль `process-manager-rest` публикует REST endpoints для диагностики процессов и ручных
операторских действий.

## Подключение

```kotlin
implementation("dev.verkhovskiy:process-manager-spring-boot-starter")
implementation("dev.verkhovskiy:process-manager-rest")
```

Autoconfiguration включается только в web application context и требует beans `ProcessInspector` и
`ProcessOperator`.

## Свойства

Префикс:

```text
process.manager.rest
```

| Свойство | Default | Назначение |
| --- | --- | --- |
| `process.manager.rest.enabled` | `true` | Включает/отключает autoconfiguration Operator REST API |

Модуль публикует Spring Boot configuration metadata, поэтому это свойство подсказывается в
Spring Boot-aware IDE.

Отключение:

```properties
process.manager.rest.enabled=false
```

## Security

REST API меняет состояние процессов через cancel/resume/retry. Модуль не настраивает security
автоматически. Приложение должно закрыть endpoints через Spring Security, gateway или внутренний
network boundary.

## Endpoints

### Details

```http
GET /process-manager/processes/{instanceId}
```

Ответ `200` содержит `ProcessDetailsView`: instance, wait points и history. Если процесса нет,
возвращается `404`.

### Cancel

```http
POST /process-manager/processes/{instanceId}/cancel
Content-Type: application/json

{
  "reason": "customer request"
}
```

Успешная отмена возвращает `200`:

```json
{
  "instanceId": "018f0000-0000-7000-8000-000000000001",
  "accepted": true
}
```

Если процесса нет, возвращается `404`. Если процесс найден, но операцию нельзя применить,
возвращается `409`.

### Manual Resume

```http
POST /process-manager/processes/{instanceId}/resume
```

Успешное планирование команды возвращает `202`. Команда создается с текущей `version` процесса.

### Manual Retry

```http
POST /process-manager/processes/{instanceId}/retry
```

Успешное планирование команды возвращает `202`. Retry допускается только для процессов в статусе
`RUNNING`.
