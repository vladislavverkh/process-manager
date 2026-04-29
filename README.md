# process-manager

`process-manager` - библиотека для durable-оркестрации бизнес-процессов: платежи,
закрытие договоров, возвраты, заявки и другие сценарии, где есть состояния, условные
переходы, retry, ожидание внешних событий и возобновление процесса после ответа из Kafka
или другого канала.

Библиотека проектируется как state-machine/process-orchestrator поверх PostgreSQL. Для
асинхронного исполнения и отложенного resume runtime использует абстракцию
`ProcessCommandScheduler`. Приложение само выбирает реализацию этой абстракции: task queue,
Kafka, scheduler в другой БД или собственный executor.

## Статус проекта

Проект находится на ранней стадии:

- есть Gradle multi-module skeleton;
- есть core-модель definition/state/transition/retry/retention;
- есть PostgreSQL schema и repository для instance/wait/inbox/history;
- есть опциональные adapter-классы для ручной интеграции с `task-queue-postgres`;
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
- есть пример Spring Boot приложения `process-manager-sample-app`;
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
- [Runnable Spring Boot sample](process-manager-sample-app/README.md)
- [Roadmap](docs/roadmap.md)

## Модули

- `process-manager-core` - модель процесса, DSL и runtime contracts.
- `process-manager-postgres` - PostgreSQL-хранилище instance, wait, inbox и history.
- `process-manager-task-queue` - опциональный adapter к `task-queue-postgres` для приложений,
  которые явно используют обе библиотеки.
- `process-manager-spring-boot-starter` - Spring Boot autoconfiguration.
- `process-manager-rest` - REST API для диагностики и ручных операторских действий.
- `process-manager-sample-app` - пример приложения с процессом обработки транзакции, PostgreSQL и REST API.
- `process-manager-testkit` - test helpers для process definitions.

## Локальная сборка

Базовая проверка не требует `task-queue-postgres`:

```bash
./gradlew check
```

`process-manager-task-queue` не включается в Gradle build автоматически. Если нужно проверить
adapter-модуль, сначала опубликуйте `task-queue-core` как обычную зависимость, например в
`mavenLocal`, после чего модуль можно включить явно:

```bash
./gradlew :process-manager-task-queue:check -PprocessManager.includeTaskQueueAdapter=true
```
