-- Auto-capture learns from corrections. An alert it couldn't read, once filled in by hand, becomes a pattern for
-- alerts like it (amount, date and name become slots; other numbers become wildcards). A name corrected in review is
-- remembered for next time. Per person: alerts carry private details, so patterns are never shared.
create table capture_patterns (
    id          uuid          primary key,
    user_id     uuid          not null references users (id),
    pattern     text          not null,
    kind        varchar(8)    not null check (kind in ('EXPENSE', 'INCOME')),
    -- The name to use when the pattern has no name slot (an alert that never says who).
    merchant    varchar(120),
    used        int           not null default 0,
    created_at  timestamptz   not null default now(),
    unique (user_id, pattern)
);

create table capture_aliases (
    user_id     uuid          not null references users (id),
    raw         varchar(120)  not null,
    name        varchar(120)  not null,
    primary key (user_id, raw)
);

-- How an entry was read: by the built-in rules or by something you taught it.
alter table captured_items add column read_by varchar(8);
