-- Guests: people in a group without an account. They prove their email with a 6-digit code and get a guest session
-- for the groups where that email is a member (product plan 3.1-3.2). Only hashes of codes and session tokens are kept.

-- An expense or payment a guest added has no user behind it; the member who added it is kept instead.
alter table group_expenses alter column created_by drop not null;
alter table group_expenses add column created_by_member uuid references group_members (id);
alter table group_settlements alter column created_by drop not null;
alter table group_settlements add column created_by_member uuid references group_members (id);

create table guest_codes (
    id          uuid primary key,
    email       varchar(320) not null,
    group_id    uuid not null references groups (id) on delete cascade,
    -- The placeholder they picked, or null to be added under {@code name}.
    member_id   uuid references group_members (id) on delete cascade,
    name        varchar(60),
    code_hash   varchar(64) not null,
    attempts    int not null default 0,
    expires_at  timestamptz not null,
    used_at     timestamptz,
    created_at  timestamptz not null default now()
);
create index guest_codes_email on guest_codes (lower(email), created_at);

create table guest_sessions (
    token_hash   varchar(64) primary key,
    email        varchar(320) not null,
    created_at   timestamptz not null default now(),
    expires_at   timestamptz not null,
    revoked_at   timestamptz,
    last_seen_at timestamptz
);
create index guest_sessions_email on guest_sessions (lower(email));
