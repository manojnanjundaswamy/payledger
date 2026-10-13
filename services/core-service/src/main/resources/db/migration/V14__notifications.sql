-- Notifications (docs/notifications.md). Emails are queued here in the same transaction as what caused them, then
-- sent by a background job with retries, so a slow or failing mail server never slows or breaks the app.
create table email_outbox (
    id          uuid          primary key,
    user_id     uuid          references users (id),
    kind        varchar(30)   not null,
    to_email    varchar(254)  not null,
    subject     varchar(200)  not null,
    body_html   text          not null,
    body_text   text          not null,
    -- The same reminder is never queued twice (e.g. "bills:<user>:2026-10-14").
    dedupe_key  varchar(200)  not null,
    created_at  timestamptz   not null default now(),
    send_after  timestamptz   not null default now(),
    sent_at     timestamptz,
    attempts    int           not null default 0,
    last_error  varchar(500),
    constraint ux_email_outbox_dedupe unique (dedupe_key)
);

create index ix_email_outbox_pending on email_outbox (send_after) where sent_at is null;
create index ix_email_outbox_user on email_outbox (user_id, created_at desc);

-- Which emails a user wants, by kind (BILL_REMINDERS, BUDGET_ALERTS, GROUP_ACTIVITY, PRODUCT_NEWS): a missing key
-- means the default (on, except product news).
alter table users add column email_prefs jsonb not null default '{}'::jsonb check (jsonb_typeof(email_prefs) = 'object');

-- The in-app inbox: the bell in the top bar. Everything that involves the user lands here (and, unless they turned
-- that kind off, in an email too). Kept 180 days.
create table inbox (
    id          uuid          primary key,
    user_id     uuid          not null references users (id),
    kind        varchar(30)   not null,
    title       varchar(200)  not null,
    body        varchar(500),
    link        varchar(300),
    actor_id    uuid          references users (id),
    created_at  timestamptz   not null default now(),
    read_at     timestamptz,
    -- The same reminder is never added twice ("bills:<user>:2026-10-14").
    dedupe_key  varchar(200),
    constraint ux_inbox_dedupe unique (dedupe_key)
);

create index ix_inbox_user on inbox (user_id, created_at desc);
create index ix_inbox_unread on inbox (user_id) where read_at is null;

-- What people did, for staff (docs/adr/0008-admin-console.md): sign-ins, and every change made through the API, by
-- route and ids, never amounts or descriptions. Kept 180 days.
create table user_events (
    id          uuid          primary key,
    user_id     uuid          not null references users (id),
    type        varchar(20)   not null check (type in ('SIGN_IN', 'CHANGE')),
    -- For a change: "POST /api/core/spaces/{spaceId}/transactions" and the response status.
    action      varchar(200),
    status      smallint,
    -- Path ids (space, transaction, group...), so staff can tell what it was about without seeing what it said.
    target      jsonb         not null default '{}'::jsonb,
    -- The Keycloak session a sign-in started.
    session_id  varchar(64),
    ip          varchar(64),
    user_agent  varchar(300),
    at          timestamptz   not null default now()
);

create index ix_user_events_user on user_events (user_id, at desc);
create index ix_user_events_at on user_events (at desc);
create unique index ux_user_events_session on user_events (user_id, session_id) where type = 'SIGN_IN';
