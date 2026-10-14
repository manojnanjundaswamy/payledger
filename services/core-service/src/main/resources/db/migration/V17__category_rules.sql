-- "Always put Swiggy under Eating out": a description that contains `match` (case-insensitive) gets this category,
-- when a transaction is added without one and when a file is imported (docs/plan/ideas-2026-10.md, idea 3).
create table category_rules (
    id           uuid          primary key,
    space_id     uuid          not null references spaces (id),
    match        varchar(60)   not null check (length(trim(match)) >= 2 and match = lower(trim(match))),
    category_id  uuid          not null references categories (id),
    created_by   uuid          not null references users (id),
    created_at   timestamptz   not null default now(),
    -- How many times it filled a category in, so the list can show which rules earn their keep.
    used         int           not null default 0,
    constraint uq_category_rules_match unique (space_id, match)
);
