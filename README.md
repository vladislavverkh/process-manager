# process-manager

`process-manager` - библиотека для durable-оркестрации бизнес-процессов: платежи,
закрытие договоров, возвраты, заявки и другие сценарии, где есть состояния, условные
переходы, retry, ожидание внешних событий и возобновление процесса после ответа из Kafka
или другого канала.

Библиотека проектируется как state-machine/process-orchestrator поверх PostgreSQL. Для
асинхронного исполнения и отложенного resume runtime использует абстракцию
`ProcessCommandScheduler`; `task-queue-postgres` подключается отдельным adapter-модулем.

## Статус проекта

Проект находится на ранней стадии:

- есть Gradle multi-module skeleton;
- есть core-модель definition/state/transition/retry/retention;
- есть PostgreSQL schema и repository для instance/wait/inbox/history;
- есть опциональный task-queue adapter для durable process commands;
- есть Spring Boot autoconfiguration;
- есть базовый execution loop для `ACTION`, `WAIT`, `DECISION`, terminal states, history и stale
  commands;
- есть сохранение action result data, explicit variable updates и last trigger data в
  `variables_json`;
- есть process/state deadlines и watchdog, который планирует timeout-команды только после
  фактического истечения дедлайна;
- есть структурная валидация process definitions и базовый testkit для проверок графа;
- есть идемпотентный повторный `start` для активного business key и optional idempotency key для
  `signal`;
- есть `ProcessInspector` для чтения instance, wait points и history без изменения процесса;
- есть `ProcessOperator` для ручной отмены и ручного планирования resume/retry;
- есть отдельный модуль `process-manager-rest` с Operator REST API;
- payload validation и полноценная retry-модель еще развиваются.

## Документация

- [Общий индекс документации](docs/process-manager-library.md)
- [Архитектура](docs/architecture.md)
- [Модель процесса и DSL](docs/process-definition.md)
- [Runtime и состояние процесса](docs/runtime-state.md)
- [PostgreSQL storage](docs/postgres-storage.md)
- [Интеграция с task-queue-postgres](docs/task-queue-integration.md)
- [Spring Boot starter](docs/spring-boot.md)
- [Operator REST API](docs/operator-rest-api.md)
- [Пример платежного процесса](docs/examples/payment-process.md)
- [Roadmap](docs/roadmap.md)

## Модули

- `process-manager-core` - модель процесса, DSL и runtime contracts.
- `process-manager-postgres` - PostgreSQL-хранилище instance, wait, inbox и history.
- `process-manager-task-queue` - опциональный adapter к `task-queue-postgres`.
- `process-manager-spring-boot-starter` - Spring Boot autoconfiguration.
- `process-manager-rest` - REST API для диагностики и ручных операторских действий.
- `process-manager-testkit` - test helpers для process definitions.

## Локальная сборка

`process-manager-task-queue` включается в Gradle build только если рядом есть
`../task-queue-postgres` или если задано свойство:

```kotlin
./gradlew check -PprocessManager.includeTaskQueueAdapter=true
```

Базовая проверка без adapter-а:

```bash
./gradlew check -PprocessManager.includeTaskQueueAdapter=false
```
