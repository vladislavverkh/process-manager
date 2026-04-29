--liquibase formatted sql

--changeset process-manager:001-create-process-manager-schema
create table if not exists pm_process_instance (
    instance_id uuid primary key,
    process_type varchar(128) not null,
    definition_version integer not null,
    payload_schema_version integer not null,
    business_key varchar(512) not null,
    state varchar(128) not null,
    status varchar(32) not null,
    payload_json jsonb not null,
    variables_json jsonb not null default '{}'::jsonb,
    started_at timestamptz not null,
    updated_at timestamptz not null,
    process_deadline_at timestamptz,
    state_entered_at timestamptz,
    state_deadline_at timestamptz,
    completed_at timestamptz,
    delete_after timestamptz,
    version bigint not null default 0
);

create unique index if not exists pm_process_instance_business_key_uq
    on pm_process_instance(process_type, business_key)
    where status in ('RUNNING', 'WAITING');

create index if not exists pm_process_instance_cleanup_idx
    on pm_process_instance(status, delete_after)
    where delete_after is not null;

create index if not exists pm_process_instance_state_idx
    on pm_process_instance(process_type, state, status);

create index if not exists pm_process_instance_process_deadline_idx
    on pm_process_instance(process_deadline_at, instance_id)
    where status in ('RUNNING', 'WAITING') and process_deadline_at is not null;

create index if not exists pm_process_instance_state_deadline_idx
    on pm_process_instance(state_deadline_at, instance_id)
    where status in ('RUNNING', 'WAITING') and state_deadline_at is not null;

create table if not exists pm_process_wait (
    wait_id uuid primary key,
    instance_id uuid not null references pm_process_instance(instance_id) on delete cascade,
    process_type varchar(128) not null,
    state varchar(128) not null,
    event_type varchar(128) not null,
    correlation_key varchar(512) not null,
    expires_at timestamptz,
    created_at timestamptz not null
);

create unique index if not exists pm_process_wait_correlation_uq
    on pm_process_wait(event_type, correlation_key, instance_id);

create index if not exists pm_process_wait_instance_idx
    on pm_process_wait(instance_id);

create table if not exists pm_process_event_inbox (
    event_id uuid primary key,
    event_type varchar(128) not null,
    correlation_key varchar(512) not null,
    idempotency_key varchar(512),
    payload_json jsonb not null,
    received_at timestamptz not null,
    consumed_at timestamptz
);

create index if not exists pm_process_event_inbox_correlation_idx
    on pm_process_event_inbox(event_type, correlation_key);

create unique index if not exists pm_process_event_inbox_idempotency_uq
    on pm_process_event_inbox(event_type, correlation_key, idempotency_key)
    where idempotency_key is not null;

create table if not exists pm_process_history (
    history_id uuid primary key,
    instance_id uuid not null references pm_process_instance(instance_id) on delete cascade,
    process_type varchar(128) not null,
    from_state varchar(128),
    to_state varchar(128),
    transition_name varchar(128),
    trigger_type varchar(64) not null,
    trigger_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null
);

create index if not exists pm_process_history_instance_idx
    on pm_process_history(instance_id, created_at);

--changeset process-manager:002-comment-process-manager-schema
comment on table pm_process_instance is 'Durable state экземпляра процесса.';
comment on column pm_process_instance.instance_id is 'Уникальный идентификатор экземпляра процесса.';
comment on column pm_process_instance.process_type is 'Тип процесса, соответствует ProcessDefinition.type.';
comment on column pm_process_instance.definition_version is 'Версия ProcessDefinition, по которой создан экземпляр.';
comment on column pm_process_instance.payload_schema_version is 'Версия схемы payload процесса.';
comment on column pm_process_instance.business_key is 'Бизнесовый ключ процесса для идемпотентного старта и корреляции.';
comment on column pm_process_instance.state is 'Текущее состояние процесса.';
comment on column pm_process_instance.status is 'Текущий статус экземпляра процесса.';
comment on column pm_process_instance.payload_json is 'Исходный payload процесса в JSONB.';
comment on column pm_process_instance.variables_json is 'Runtime variables процесса и данные последних action/event triggers.';
comment on column pm_process_instance.started_at is 'Время создания экземпляра процесса.';
comment on column pm_process_instance.updated_at is 'Время последнего обновления экземпляра процесса.';
comment on column pm_process_instance.process_deadline_at is 'Абсолютный дедлайн всего процесса.';
comment on column pm_process_instance.state_entered_at is 'Время входа в текущее состояние.';
comment on column pm_process_instance.state_deadline_at is 'Абсолютный дедлайн текущего состояния.';
comment on column pm_process_instance.completed_at is 'Время завершения процесса.';
comment on column pm_process_instance.delete_after is 'Время, после которого процесс можно удалить retention cleanup.';
comment on column pm_process_instance.version is 'Версия строки для optimistic locking и защиты от stale commands.';

comment on table pm_process_wait is 'Активные точки ожидания внешних событий.';
comment on column pm_process_wait.wait_id is 'Уникальный идентификатор wait point.';
comment on column pm_process_wait.instance_id is 'Идентификатор экземпляра процесса, который ожидает событие.';
comment on column pm_process_wait.process_type is 'Тип процесса для диагностики и фильтрации wait points.';
comment on column pm_process_wait.state is 'Состояние процесса, создавшее wait point.';
comment on column pm_process_wait.event_type is 'Тип ожидаемого внешнего события.';
comment on column pm_process_wait.correlation_key is 'Ключ корреляции ожидаемого события с процессом.';
comment on column pm_process_wait.expires_at is 'Время истечения ожидания события.';
comment on column pm_process_wait.created_at is 'Время создания wait point.';

comment on table pm_process_event_inbox is 'Inbox внешних событий с идемпотентностью доставки.';
comment on column pm_process_event_inbox.event_id is 'Уникальный идентификатор inbox-события.';
comment on column pm_process_event_inbox.event_type is 'Тип принятого внешнего события.';
comment on column pm_process_event_inbox.correlation_key is 'Ключ корреляции события с wait points.';
comment on column pm_process_event_inbox.idempotency_key is 'Ключ идемпотентности внешней доставки события.';
comment on column pm_process_event_inbox.payload_json is 'Payload внешнего события в JSONB.';
comment on column pm_process_event_inbox.received_at is 'Время приема события runtime.';
comment on column pm_process_event_inbox.consumed_at is 'Время успешного сопоставления события с процессом.';

comment on table pm_process_history is 'История переходов процесса и runtime triggers.';
comment on column pm_process_history.history_id is 'Уникальный идентификатор записи истории.';
comment on column pm_process_history.instance_id is 'Идентификатор экземпляра процесса.';
comment on column pm_process_history.process_type is 'Тип процесса для диагностики истории.';
comment on column pm_process_history.from_state is 'Состояние, из которого выполнен переход.';
comment on column pm_process_history.to_state is 'Состояние, в которое выполнен переход.';
comment on column pm_process_history.transition_name is 'Имя transition, выбранного runtime.';
comment on column pm_process_history.trigger_type is 'Тип trigger, вызвавшего переход.';
comment on column pm_process_history.trigger_json is 'Данные trigger в JSONB.';
comment on column pm_process_history.created_at is 'Время записи события истории.';
