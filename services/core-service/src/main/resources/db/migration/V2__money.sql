-- =====================================================================
-- Money: accounts, categories, transactions (docs/plan/product-plan.md, section 2.1)
--
-- core-service owns what the user typed. Balances are NOT stored here: every write
-- also queues a ledger command in outbox_events (same transaction), and ledger-service
-- is the only source of truth for balances (docs/adr/0005-ledger-v2-books.md).
-- =====================================================================

-- Where money lives. BANK, CASH and WALLET are assets; CARD is a liability (what you owe).
create table money_accounts (
    id               uuid          primary key,
    space_id         uuid          not null references spaces (id),
    kind             varchar(16)   not null check (kind in ('BANK', 'CASH', 'CARD', 'WALLET')),
    name             varchar(60)   not null,
    currency         varchar(3)    not null check (currency ~ '^[A-Z]{3}$'),
    -- In the user's terms: money in the account, or for a CARD the amount owed. Paise.
    opening_balance  bigint        not null default 0,
    opening_date     date          not null,
    -- Bumped on every change to the opening balance; part of the ledger idempotency key.
    opening_version  int           not null default 1,
    archived_at      timestamptz,
    created_by       uuid          not null references users (id),
    created_at       timestamptz   not null default now(),
    updated_at       timestamptz   not null default now()
);

create index ix_money_accounts_space on money_accounts (space_id);

-- The starting set every space gets. One list, used by this migration for existing
-- spaces and by core-service for new ones, so the two can't drift apart.
create table default_categories (
    slug        varchar(40)   primary key,
    kind        varchar(8)    not null check (kind in ('EXPENSE', 'INCOME')),
    name        varchar(40)   not null,
    icon        varchar(32)   not null,
    color       varchar(16)   not null,
    sort_order  smallint      not null
);

insert into default_categories (slug, kind, name, icon, color, sort_order) values
    ('groceries',      'EXPENSE', 'Groceries',          'shopping-basket', 'series-1',  10),
    ('eating-out',     'EXPENSE', 'Eating out',         'utensils',        'series-2',  20),
    ('rent',           'EXPENSE', 'Rent',               'house',           'series-3',  30),
    ('utilities',      'EXPENSE', 'Utilities',          'zap',             'series-4',  40),
    ('transport',      'EXPENSE', 'Fuel & transport',   'car',             'series-5',  50),
    ('shopping',       'EXPENSE', 'Shopping',           'shopping-bag',    'series-6',  60),
    ('health',         'EXPENSE', 'Health',             'heart-pulse',     'series-7',  70),
    ('education',      'EXPENSE', 'Education',          'graduation-cap',  'series-8',  80),
    ('subscriptions',  'EXPENSE', 'Subscriptions',      'repeat',          'series-1',  90),
    ('emi',            'EXPENSE', 'EMIs & loans',       'landmark',        'series-2', 100),
    ('insurance',      'EXPENSE', 'Insurance',          'shield',          'series-3', 110),
    ('family',         'EXPENSE', 'Family & gifts',     'gift',            'series-4', 120),
    ('travel',         'EXPENSE', 'Travel',             'plane',           'series-5', 130),
    ('household-help', 'EXPENSE', 'Household help',     'hand-helping',    'series-6', 140),
    ('other-expense',  'EXPENSE', 'Other',              'circle-dashed',   'series-8', 999),
    ('salary',         'INCOME',  'Salary',             'briefcase',       'series-1',  10),
    ('business',       'INCOME',  'Business',           'store',           'series-2',  20),
    ('interest',       'INCOME',  'Interest & dividends','percent',        'series-3',  30),
    ('refunds',        'INCOME',  'Refunds & cashback', 'undo-2',          'series-4',  40),
    ('other-income',   'INCOME',  'Other',              'circle-dashed',   'series-8', 999);

-- A category is an EXPENSE or INCOME account in the space's ledger book (ledger ref category:<id>).
create table categories (
    id          uuid          primary key,
    space_id    uuid          not null references spaces (id),
    kind        varchar(8)    not null check (kind in ('EXPENSE', 'INCOME')),
    name        varchar(40)   not null,
    icon        varchar(32)   not null,
    color       varchar(16)   not null,
    parent_id   uuid          references categories (id),
    sort_order  smallint      not null default 500,
    -- Hidden categories keep their history but are not offered for new transactions.
    hidden      boolean       not null default false,
    -- Which default it came from, so seeding is idempotent.
    seed_slug   varchar(40),
    created_at  timestamptz   not null default now(),
    constraint ux_categories_seed unique (space_id, seed_slug)
);

create index ix_categories_space on categories (space_id);

insert into categories (id, space_id, kind, name, icon, color, sort_order, seed_slug)
select gen_random_uuid(), s.id, d.kind, d.name, d.icon, d.color, d.sort_order, d.slug
from spaces s cross join default_categories d;

-- What the user entered. The ledger holds the double-entry postings; this row is the
-- editable version. Each edit bumps version: the ledger reverses version n and posts n+1.
create table transactions (
    id              uuid          primary key,
    space_id        uuid          not null references spaces (id),
    kind            varchar(8)    not null check (kind in ('EXPENSE', 'INCOME', 'TRANSFER')),
    -- Always positive, in the account's currency (paise). The kind gives the direction.
    amount          bigint        not null check (amount > 0),
    currency        varchar(3)    not null check (currency ~ '^[A-Z]{3}$'),
    account_id      uuid          not null references money_accounts (id),
    -- TRANSFER only: where the money went.
    to_account_id   uuid          references money_accounts (id),
    -- EXPENSE and INCOME only.
    category_id     uuid          references categories (id),
    occurred_on     date          not null,
    description     varchar(120)  not null,
    note            varchar(1000),
    tags            text[]        not null default '{}',
    version         int           not null default 1,
    -- The version whose ledger entry is live. Edits to description, note or tags bump version
    -- but not this, so they don't churn the ledger; the next money edit reverses this one.
    ledger_version  int           not null default 1,
    deleted_at      timestamptz,
    created_by      uuid          not null references users (id),
    created_at      timestamptz   not null default now(),
    updated_at      timestamptz   not null default now(),
    constraint ck_transactions_shape check (
        (kind = 'TRANSFER' and to_account_id is not null and category_id is null and to_account_id <> account_id)
        or (kind <> 'TRANSFER' and to_account_id is null and category_id is not null)
    )
);

-- The list screen: newest first within a space, keyset-paginated on (occurred_on, id).
create index ix_transactions_space_date on transactions (space_id, occurred_on desc, id desc) where deleted_at is null;
create index ix_transactions_account on transactions (account_id);
create index ix_transactions_to_account on transactions (to_account_id) where to_account_id is not null;
create index ix_transactions_category on transactions (category_id);

-- Transactional outbox (same design as ledger-service, docs/adr/0004-transactional-outbox.md).
-- Here the rows are ledger COMMANDS: ledger-service consumes them and posts idempotently.
create table outbox_events (
    id              bigint        generated always as identity primary key,
    event_id        uuid          not null,
    aggregate_type  varchar(32)   not null,
    -- Kafka key. Commands are keyed by space so one book's commands stay in order
    -- (a reversal is never processed before the entry it reverses).
    aggregate_id    uuid          not null,
    event_type      varchar(64)   not null,
    payload         jsonb         not null,
    created_at      timestamptz   not null default now(),
    published_at    timestamptz,
    constraint ux_outbox_events_event_id unique (event_id)
);

create index ix_outbox_events_unpublished on outbox_events (id) where published_at is null;
