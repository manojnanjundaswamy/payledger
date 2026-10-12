-- One row per (event, user wallet): a transfer between alice and bob yields two notifications.
create table notifications (
    id             bigint        generated always as identity primary key,
    event_id       uuid          not null,
    entry_id       uuid          not null,
    account_id     uuid          not null,
    owner_id       varchar(64)   not null,
    entry_type     varchar(16)   not null,
    -- Signed minor units, as in the ledger: positive = money in.
    amount         bigint        not null,
    currency       varchar(3)    not null,
    balance_after  bigint        not null,
    message        varchar(255)  not null,
    occurred_at    timestamptz   not null,
    created_at     timestamptz   not null default now(),
    -- Idempotent consumer: Kafka delivers at-least-once, so the same event can arrive twice
    -- (relay re-send, consumer rebalance before the offset commit). The second insert is a no-op.
    constraint ux_notifications_event_account unique (event_id, account_id)
);

-- Feed query: "latest notifications for owner X" with keyset pagination on id.
create index ix_notifications_owner_id_desc on notifications (owner_id, id desc);
