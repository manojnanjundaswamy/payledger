-- Daily services (product plan 2.1): milk, newspaper, a cook, a maid. A rate per day (milk: what a normal day's
-- delivery costs) or per month (the cook's salary, less leave beyond what's allowed). Only the days that differ from
-- normal are kept: 0 = missed, 2 = double, and so on. Paying a month records one expense and remembers it.
create table daily_services (
    id           uuid primary key,
    space_id     uuid not null references spaces (id) on delete cascade,
    name         varchar(60) not null,
    emoji        varchar(16),
    rate         bigint not null check (rate > 0),
    per          varchar(5) not null check (per in ('DAY', 'MONTH')),
    -- MONTH only: days off a month that are still paid.
    paid_leaves  int not null default 0 check (paid_leaves between 0 and 31),
    account_id   uuid references money_accounts (id),
    category_id  uuid references categories (id),
    started_on   date not null,
    ended_on     date,
    created_by   uuid references users (id),
    created_at   timestamptz not null default now()
);
create index daily_services_space on daily_services (space_id);

create table daily_service_days (
    service_id  uuid not null references daily_services (id) on delete cascade,
    day         date not null,
    quantity    int not null check (quantity between 0 and 9),
    primary key (service_id, day)
);

create table daily_service_payments (
    service_id      uuid not null references daily_services (id) on delete cascade,
    month           date not null,
    transaction_id  uuid not null references transactions (id),
    amount          bigint not null,
    created_at      timestamptz not null default now(),
    primary key (service_id, month)
);
