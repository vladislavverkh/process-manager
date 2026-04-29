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
