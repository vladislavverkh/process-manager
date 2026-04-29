# process-manager-sample-app

Минимальное Spring Boot приложение, которое использует `process-manager` для обработки
транзакции.

В примере есть два сценария обработки:

- `sample-transaction-polling` - после `202 Accepted` процесс сам периодически опрашивает stub-сервис
  проводок через `TIMER`;
- `sample-transaction-event` - после отправки команды процесс ждет внешнее событие, как будто ответ
  пришел из парного Kafka topic.

Общие шаги:

1. Проверить, что тип транзакции поддерживается.
2. Найти клиента во внешней системе.
3. Найти договор во внешней системе.
4. Создать внутренние действия по транзакции.
5. Найти макет будущей бухгалтерской проводки и подобрать счета дебета/кредита.
6. Отправить команду на формирование проводки.
7. Дождаться финального результата через polling или внешнее событие.

Внешние REST/Kafka системы в примере заменены in-memory stubs, чтобы приложение запускалось без
дополнительных сервисов кроме PostgreSQL.

Действия по транзакции - это бизнесовые данные sample app. Они хранятся отдельно от process-manager
state в таблице `sample_transaction_action` и создаются внутренним шагом
`BUILD_TRANSACTION_ACTIONS`.

## Запуск PostgreSQL

```bash
docker compose -f process-manager-sample-app/docker-compose.yml up -d
```

Compose публикует PostgreSQL на `localhost:54320`, чтобы не конфликтовать с локальным PostgreSQL на
стандартном порту `5432`.

Если контейнер уже запускался до добавления Liquibase, пересоздайте volume:

```bash
docker compose -f process-manager-sample-app/docker-compose.yml down -v
docker compose -f process-manager-sample-app/docker-compose.yml up -d
```

## Запуск приложения

```bash
./gradlew :process-manager-sample-app:bootRun
```

При старте приложение накатывает два Liquibase changelog:

- `classpath:db/changelog/process-manager.postgres.sql` - runtime tables библиотеки;
- `classpath:db/changelog/sample-app.postgres.sql` - бизнесовые таблицы sample app.

Swagger UI:

```text
http://localhost:8080/swagger-ui.html
```

OpenAPI JSON:

```text
http://localhost:8080/v3/api-docs
```

## Happy Path: Polling

`completionMode` можно не передавать: по умолчанию используется `POLLING`.

```bash
curl -X POST http://localhost:8080/sample/transactions \
  -H 'Content-Type: application/json' \
  -d '{
    "transactionId": "tx-1",
    "transactionDate": "2026-04-29",
    "contractNumber": "CONTRACT-1",
    "transactionType": "ACCRUAL",
    "completionMode": "POLLING"
  }'
```

После отправки команды процесс перейдет в `WAIT_NEXT_POSTING_POLL`, а затем будет выполнять
`POLL_POSTING_RESULT` через timer state до финального ответа stub-сервиса проводок.

## Happy Path: Event From Kafka

Создать транзакцию в event-based сценарии:

```bash
curl -X POST http://localhost:8080/sample/transactions \
  -H 'Content-Type: application/json' \
  -d '{
    "transactionId": "tx-event-1",
    "transactionDate": "2026-04-29",
    "contractNumber": "CONTRACT-1",
    "transactionType": "ACCRUAL",
    "completionMode": "EVENT"
  }'
```

Передать результат формирования проводки, как будто он пришел из парного Kafka topic:

```bash
curl -X POST http://localhost:8080/sample/transactions/tx-event-1/posting-result \
  -H 'Content-Type: application/json' \
  -d '{
    "posted": true,
    "postingId": "posting-1",
    "idempotencyKey": "posting-result-1"
  }'
```

## Business Error

Неподдерживаемый тип транзакции сразу переведет процесс в `BUSINESS_ERROR`:

```bash
curl -X POST http://localhost:8080/sample/transactions \
  -H 'Content-Type: application/json' \
  -d '{
    "transactionId": "tx-unsupported",
    "transactionDate": "2026-04-29",
    "contractNumber": "CONTRACT-2",
    "transactionType": "UNKNOWN"
  }'
```

Business failures от внешних систем можно проверить префиксами:

- `NO-CLIENT-1` - клиент не найден;
- `NO-CONTRACT-1` - договор не найден;
- `NO-TEMPLATE-1` - макет проводки не найден.
- `POSTING-REJECT-1` - polling-сервис проводок вернет финальный бизнесовый отказ.

## Temporary Error And Park

Префикс `TMP-CLIENT-1`, `TMP-CONTRACT-1` или `TMP-ACCOUNTS-1` имитирует постоянную временную
ошибку REST-сервиса. Runtime выполнит retry, после exhaustion процесс перейдет в state
`PARKED_TEMPORARY_FAILURE` со статусом `WAITING`.

```bash
curl -X POST http://localhost:8080/sample/transactions \
  -H 'Content-Type: application/json' \
  -d '{
    "transactionId": "tx-temp-client",
    "transactionDate": "2026-04-29",
    "contractNumber": "TMP-CLIENT-1",
    "transactionType": "ACCRUAL"
  }'
```

Префиксы `FLAKY-CLIENT-1`, `FLAKY-CONTRACT-1`, `FLAKY-ACCOUNTS-1` имитируют временную ошибку,
которая проходит после нескольких retry.

Ручной retry припаркованного процесса:

```bash
curl -X POST http://localhost:8080/sample/transactions/tx-temp-client/retry \
  -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"manual-retry-1"}'
```

## Диагностика

Список процессов:

```bash
curl 'http://localhost:8080/sample/transactions'
```

Список бизнесовых действий по транзакции:

```bash
curl 'http://localhost:8080/sample/transactions/tx-1/actions'
```
