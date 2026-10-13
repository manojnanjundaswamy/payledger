-- Shared expenses and settlements in a group (docs/adr/0007-groups-and-splitting.md). The ledger's group
-- book holds the money; these rows are what people entered, and are what an edit replaces.
create table group_expenses (
    id              uuid          primary key,
    group_id        uuid          not null references groups (id),
    description     varchar(120)  not null,
    amount          bigint        not null check (amount > 0),
    currency        varchar(3)    not null check (currency ~ '^[A-Z]{3}$'),
    occurred_on     date          not null,
    -- A label for the group ("Food"); each member maps it to their own category later.
    category        varchar(40),
    split           varchar(8)    not null check (split in ('EQUAL', 'EXACT', 'PERCENT', 'SHARES')),
    version         int           not null default 1,
    -- The version whose ledger entry is live; 0 when nothing moved (one person paid their own share).
    ledger_version  int           not null default 1,
    created_by      uuid          not null references users (id),
    created_at      timestamptz   not null default now(),
    updated_at      timestamptz   not null default now(),
    deleted_at      timestamptz
);

create index ix_group_expenses_group on group_expenses (group_id, occurred_on desc, id desc) where deleted_at is null;

-- Each member's part of an expense: what they paid and their share (both exact paise), and what was typed
-- for the split (paise, basis points or shares) so the form can show it again.
create table group_expense_parts (
    expense_id  uuid      not null references group_expenses (id),
    member_id   uuid      not null references group_members (id),
    paid        bigint    not null check (paid >= 0),
    share       bigint    not null check (share >= 0),
    input       bigint,
    position    smallint  not null,
    primary key (expense_id, member_id)
);

create index ix_group_expense_parts_member on group_expense_parts (member_id);

create table group_settlements (
    id           uuid          primary key,
    group_id     uuid          not null references groups (id),
    from_member  uuid          not null references group_members (id),
    to_member    uuid          not null references group_members (id),
    amount       bigint        not null check (amount > 0),
    currency     varchar(3)    not null check (currency ~ '^[A-Z]{3}$'),
    occurred_on  date          not null,
    note         varchar(120),
    created_by   uuid          not null references users (id),
    created_at   timestamptz   not null default now(),
    deleted_at   timestamptz,
    constraint ck_group_settlements_two check (from_member <> to_member)
);

create index ix_group_settlements_group on group_settlements (group_id, occurred_on desc) where deleted_at is null;
