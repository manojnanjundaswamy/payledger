-- Budgets: a monthly spending limit per expense category (a parent's limit covers its subcategories).
-- A row sets the limit from one budget month onwards, until the category's next row; a null amount
-- ends the budget. So changing the limit in November leaves October's budget as it was.
-- A budget month is named by the month its period starts in: with the user's budget start day 25,
-- "2026-10" runs from 25 Oct to 24 Nov. Naming by month keeps history right if the start day changes.
create table budget_limits (
    space_id        uuid          not null references spaces (id),
    category_id     uuid          not null references categories (id),
    -- Always the first day of a month.
    effective_month date          not null check (extract(day from effective_month) = 1),
    amount          bigint        check (amount is null or amount > 0),
    currency        varchar(3)    not null check (currency ~ '^[A-Z]{3}$'),
    updated_by      uuid          not null references users (id),
    updated_at      timestamptz   not null default now(),
    primary key (category_id, effective_month)
);

create index ix_budget_limits_space on budget_limits (space_id, effective_month);
