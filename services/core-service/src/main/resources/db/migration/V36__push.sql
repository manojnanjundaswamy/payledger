-- Web Push (RFC 8030/8291/8292): each browser or installed app that turned notifications on, and the server's own
-- signing keys (VAPID), made once on first use and kept here so they survive restarts.
create table push_subscriptions (
    id          uuid primary key,
    user_id     uuid not null references users (id) on delete cascade,
    endpoint    varchar(1000) not null unique,
    p256dh      varchar(200) not null,
    auth        varchar(100) not null,
    user_agent  varchar(300),
    created_at  timestamptz not null default now(),
    last_ok_at  timestamptz,
    failures    int not null default 0
);
create index push_subscriptions_user on push_subscriptions (user_id);

create table push_keys (
    id           int primary key check (id = 1),
    public_key   varchar(200) not null,
    private_key  varchar(200) not null,
    created_at   timestamptz not null default now()
);
