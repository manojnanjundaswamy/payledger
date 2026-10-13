-- Commitments: money that leaves on a schedule (product plan: EMIs, SIPs, insurance, subscriptions).
--   BILL    an expense in a category, paid from an account: EMI, rent, insurance, school fees, Netflix.
--   SAVING  a transfer to one of your own accounts: SIP, RD, a monthly top-up of the emergency fund.
-- Due dates are first_due plus a multiple of every_months (always counted from first_due, so the 31st
-- falls on the 28th in February and is back on the 31st in March), up to ends_on if there is one.
create table commitments (
    id             uuid          primary key,
    space_id       uuid          not null references spaces (id),
    kind           varchar(8)    not null check (kind in ('BILL', 'SAVING')),
    name           varchar(60)   not null,
    -- Paise, in the paying account's currency.
    amount         bigint        not null check (amount > 0),
    currency       varchar(3)    not null check (currency ~ '^[A-Z]{3}$'),
    account_id     uuid          not null references money_accounts (id),
    category_id    uuid          references categories (id),
    to_account_id  uuid          references money_accounts (id),
    every_months   smallint      not null check (every_months in (1, 2, 3, 4, 6, 12)),
    first_due      date          not null,
    -- The last date it can be due (an EMI's last instalment); null: no end.
    ends_on        date          check (ends_on is null or ends_on >= first_due),
    version        int           not null default 1,
    created_by     uuid          not null references users (id),
    created_at     timestamptz   not null default now(),
    updated_at     timestamptz   not null default now(),
    constraint ck_commitments_shape check (
        (kind = 'BILL' and category_id is not null and to_account_id is null)
        or (kind = 'SAVING' and to_account_id is not null and category_id is null and to_account_id <> account_id)
    )
);

create index ix_commitments_space on commitments (space_id);

-- Which transaction paid which due date. Deleting that transaction makes the date unpaid again
-- (reads ignore deleted transactions), so "undo" is just deleting the payment.
create table commitment_payments (
    commitment_id  uuid          not null references commitments (id) on delete cascade,
    due_date       date          not null,
    transaction_id uuid          not null references transactions (id),
    created_at     timestamptz   not null default now(),
    primary key (commitment_id, due_date)
);
