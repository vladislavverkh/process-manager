--liquibase formatted sql

--changeset process-manager-sample:001-create-transaction-action
create table if not exists sample_transaction_action (
    action_id varchar(128) primary key,
    action_date date not null,
    action_type varchar(128) not null,
    contract_number varchar(128) not null,
    amount numeric(19, 2) not null,
    account_type varchar(64) not null,
    transaction_id varchar(128) not null
);

create index if not exists sample_transaction_action_transaction_idx
    on sample_transaction_action(transaction_id);

comment on table sample_transaction_action is 'Бизнесовые действия, сформированные внутренним шагом обработки транзакции.';
comment on column sample_transaction_action.action_id is 'Уникальный идентификатор действия.';
comment on column sample_transaction_action.action_date is 'Дата действия.';
comment on column sample_transaction_action.action_type is 'Тип действия.';
comment on column sample_transaction_action.contract_number is 'Номер договора.';
comment on column sample_transaction_action.amount is 'Сумма действия.';
comment on column sample_transaction_action.account_type is 'Тип счета.';
comment on column sample_transaction_action.transaction_id is 'Идентификатор исходной транзакции.';
