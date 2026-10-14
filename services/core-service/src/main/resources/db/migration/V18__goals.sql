-- Savings goals (docs/plan/ideas-2026-10.md, idea 6): save an amount by a date. Progress is either a linked account's
-- balance (an RD or a savings account kept for it) or the money added to the goal by hand.
create table goals (
    id           uuid          primary key,
    space_id     uuid          not null references spaces (id),
    name         varchar(60)   not null,
    emoji        varchar(16),
    target       bigint        not null check (target > 0),
    target_date  date,
    account_id   uuid          references money_accounts (id),
    version      int           not null default 1,
    created_by   uuid          not null references users (id),
    created_at   timestamptz   not null default now(),
    -- Reached or given up: kept for history, no longer counted or shown with the others.
    archived_at  timestamptz
);

create index ix_goals_space on goals (space_id) where archived_at is null;

-- Money put towards (or taken out of, when negative) a goal that isn't tied to an account.
create table goal_contributions (
    id           uuid          primary key,
    goal_id      uuid          not null references goals (id),
    amount       bigint        not null check (amount <> 0),
    occurred_on  date          not null,
    note         varchar(120),
    created_by   uuid          not null references users (id),
    created_at   timestamptz   not null default now()
);

create index ix_goal_contributions_goal on goal_contributions (goal_id, occurred_on desc);
