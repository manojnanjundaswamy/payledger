-- Households (product plan 2.4): a second space people share. spaces and space_members (V1) already allow it; this
-- adds email invites. Only the token's SHA-256 is stored; the link carries the token itself.
create table space_invites (
    id           uuid          primary key,
    space_id     uuid          not null references spaces (id),
    email        varchar(254)  not null,
    role         varchar(16)   not null check (role in ('PARTNER', 'MEMBER', 'VIEWER')),
    token_hash   varchar(64)   not null unique,
    invited_by   uuid          not null references users (id),
    created_at   timestamptz   not null default now(),
    expires_at   timestamptz   not null,
    accepted_at  timestamptz,
    revoked_at   timestamptz
);
create index ix_space_invites_space on space_invites (space_id);
-- One open invite per address per household.
create unique index ux_space_invites_open on space_invites (space_id, lower(email)) where accepted_at is null and revoked_at is null;
