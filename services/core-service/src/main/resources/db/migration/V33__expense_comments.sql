-- Comments on a group expense ("was this the dinner on Friday?"), by any member, guests included.
create table group_comments (
    id          uuid primary key,
    group_id    uuid not null references groups (id) on delete cascade,
    expense_id  uuid not null references group_expenses (id) on delete cascade,
    member_id   uuid not null references group_members (id),
    body        varchar(500) not null,
    created_at  timestamptz not null default now(),
    deleted_at  timestamptz
);
create index group_comments_group on group_comments (group_id, created_at);
