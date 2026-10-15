-- Every email that reached someone's address, and what came of it (added, skipped as not a transaction, Gmail's
-- confirmation...), so the Auto-capture page can say so. Sender and subject only; bodies of skipped emails aren't kept.
-- The newest 50 per person are kept.
create table inbound_emails (
    id           uuid primary key,
    user_id      uuid not null references users (id) on delete cascade,
    received_at  timestamptz not null default now(),
    sender       varchar(254),
    subject      varchar(200),
    result       varchar(30) not null,
    item_id      uuid references captured_items (id) on delete set null
);
create index inbound_emails_user on inbound_emails (user_id, received_at desc);
