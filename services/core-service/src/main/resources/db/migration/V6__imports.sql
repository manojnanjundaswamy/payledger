-- Imports from the template (product plan 2.5). Each import is a batch that can be undone as one.
create table imports (
    id          uuid          primary key,
    space_id    uuid          not null references spaces (id),
    file_name   varchar(200)  not null,
    imported    int           not null,
    duplicates  int           not null,
    skipped     int           not null,
    created_by  uuid          not null references users (id),
    created_at  timestamptz   not null default now(),
    undone_at   timestamptz
);

create index ix_imports_space on imports (space_id, created_at desc);

-- Which import added a transaction, and the source's own id for it (a bank reference): re-importing
-- the same file finds it and skips it. Undoing an import deletes its transactions, which frees the ids.
alter table transactions add column import_id uuid references imports (id);
alter table transactions add column external_id varchar(100);
create unique index ux_transactions_external_id on transactions (space_id, external_id)
    where external_id is not null and deleted_at is null;
create index ix_transactions_import on transactions (import_id) where import_id is not null;
