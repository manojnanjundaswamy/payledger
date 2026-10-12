-- =====================================================================
-- Users and spaces (docs/plan/product-plan.md, sections 2.4 and 2.6)
--
-- Identity (passwords, email verification, sessions) lives in Keycloak.
-- This table only maps Keycloak's subject to our own user id and keeps
-- product settings. A row is created the first time a person calls the API.
-- =====================================================================

create table users (
    id                uuid          primary key,
    -- Keycloak's stable user id ("sub" claim). Email can change; this can't.
    subject           varchar(64)   not null,
    email             varchar(254)  not null,
    display_name      varchar(100)  not null,
    -- India first, global-ready: every user carries locale, time zone and currency.
    locale            varchar(16)   not null default 'en-IN',
    time_zone         varchar(64)   not null default 'Asia/Kolkata',
    currency          varchar(3)    not null default 'INR' check (currency ~ '^[A-Z]{3}$'),
    -- The budget month can start on payday (e.g. the 25th). Capped at 28 so every month has that day.
    budget_start_day  smallint      not null default 1 check (budget_start_day between 1 and 28),
    -- UI customisation (theme, accent colour, density, dashboard layout). Opaque to the server.
    preferences       jsonb         not null default '{}'::jsonb check (jsonb_typeof(preferences) = 'object'),
    created_at        timestamptz   not null default now(),
    updated_at        timestamptz   not null default now(),
    constraint ux_users_subject unique (subject),
    constraint ux_users_email unique (email)
);

-- A space is a set of money that is managed together: everyone gets a PERSONAL space;
-- a HOUSEHOLD space is shared by family members (Phase 5).
create table spaces (
    id          uuid          primary key,
    kind        varchar(16)   not null check (kind in ('PERSONAL', 'HOUSEHOLD')),
    name        varchar(100)  not null,
    currency    varchar(3)    not null check (currency ~ '^[A-Z]{3}$'),
    created_by  uuid          not null references users (id),
    created_at  timestamptz   not null default now()
);

-- Exactly one personal space per user.
create unique index ux_spaces_personal_owner on spaces (created_by) where kind = 'PERSONAL';

create table space_members (
    space_id   uuid          not null references spaces (id),
    user_id    uuid          not null references users (id),
    role       varchar(16)   not null check (role in ('OWNER', 'PARTNER', 'MEMBER', 'VIEWER')),
    joined_at  timestamptz   not null default now(),
    primary key (space_id, user_id)
);

create index ix_space_members_user on space_members (user_id);
