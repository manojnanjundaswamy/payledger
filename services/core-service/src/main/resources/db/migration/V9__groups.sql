-- Groups for splitting (docs/adr/0007-groups-and-splitting.md).
create table groups (
    id          uuid          primary key,
    name        varchar(60)   not null,
    emoji       varchar(16),
    currency    varchar(3)    not null check (currency ~ '^[A-Z]{3}$'),
    created_by  uuid          not null references users (id),
    created_at  timestamptz   not null default now(),
    archived_at timestamptz
);

-- A person in a group. user_id is null for a placeholder (added by name, or invited by email and not
-- signed up yet). The name is the one given in the group, never taken from the user's account.
create table group_members (
    id           uuid          primary key,
    group_id     uuid          not null references groups (id),
    user_id      uuid          references users (id),
    display_name varchar(60)   not null,
    email        varchar(254),
    role         varchar(8)    not null check (role in ('OWNER', 'MEMBER')),
    added_by     uuid          references users (id),
    joined_at    timestamptz,
    left_at      timestamptz,
    created_at   timestamptz   not null default now()
);

create unique index ux_group_members_user on group_members (group_id, user_id) where user_id is not null and left_at is null;
create unique index ux_group_members_email on group_members (group_id, email) where email is not null and left_at is null;
create index ix_group_members_user on group_members (user_id) where user_id is not null;
create index ix_group_members_email on group_members (email) where user_id is null and email is not null;

-- The join link: a random token per group, replaced (and the old one revoked) when the link is reset.
create table group_links (
    token       varchar(43)   primary key,
    group_id    uuid          not null references groups (id),
    created_by  uuid          not null references users (id),
    created_at  timestamptz   not null default now(),
    revoked_at  timestamptz
);

create unique index ux_group_links_live on group_links (group_id) where revoked_at is null;
