-- Getting entries in without typing (docs/plan/onboarding-and-features.md): bank alerts forwarded to a person's own
-- address, or SMS pasted or shared, are read and wait in "To review" until the person adds or dismisses them.

-- Each person's forwarding address is <token>@<capture domain>. The latest Gmail forwarding confirmation is kept so
-- the app can show its code: Gmail sends it to the new address before it will forward anything.
create table capture_addresses (
    user_id            uuid          primary key references users (id),
    token              varchar(24)   not null unique,
    created_at         timestamptz   not null default now(),
    confirmation_code  varchar(32),
    confirmation_link  text,
    confirmation_at    timestamptz
);

create table captured_items (
    id              uuid          primary key,
    user_id         uuid          not null references users (id),
    space_id        uuid          not null references spaces (id),
    source          varchar(8)    not null check (source in ('EMAIL', 'PASTE', 'SHARE')),
    status          varchar(12)   not null default 'PENDING' check (status in ('PENDING', 'ADDED', 'DISMISSED', 'UNREADABLE')),
    sender          varchar(254),
    subject         varchar(300),
    -- The text as received, for "show the original". Cleared once the item is added or dismissed (keep no more than needed).
    body            text,
    -- SHA-256 of the email's Message-ID, or of the text: the same alert twice is kept once.
    fingerprint     varchar(64)   not null,
    kind            varchar(8)    check (kind in ('EXPENSE', 'INCOME')),
    amount          bigint        check (amount > 0),
    occurred_on     date,
    merchant        varchar(120),
    bank            varchar(40),
    account_hint    varchar(8),
    balance         bigint,
    transaction_id  uuid          references transactions (id),
    received_at     timestamptz   not null default now(),
    decided_at      timestamptz,
    unique (user_id, fingerprint)
);
create index ix_captured_items_review on captured_items (user_id, status, received_at desc);
