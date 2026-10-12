-- =====================================================================
-- Ledger v2: a generic double-entry engine (see docs/adr/0005-ledger-v2-books.md)
--
--   book     one set of accounts that balance together: a person's space,
--            a household, or a splitting group. Identified by the caller's
--            reference (e.g. 'space:<uuid>'), so callers never store ledger ids.
--   account  ASSET / LIABILITY / EQUITY / INCOME / EXPENSE, also addressed
--            by a caller reference, one per (book, ref, currency).
--   entry    balanced postings inside ONE book, with a business date
--            (effective_date) that can be in the past.
--
-- Sign convention for v2 accounts: positive = debit, negative = credit.
-- Assets and expenses normally carry positive balances; liabilities,
-- equity and income normally carry negative ones.
--
-- Pre-pivot wallet data (USER / SYSTEM accounts) is kept, untouched, in a
-- single LEGACY book. Nothing creates USER or SYSTEM accounts any more.
-- =====================================================================

create table books (
    id          uuid          primary key,
    ref         varchar(128)  not null,
    kind        varchar(16)   not null check (kind in ('PERSONAL', 'HOUSEHOLD', 'GROUP', 'LEGACY')),
    created_at  timestamptz   not null default now(),
    constraint ux_books_ref unique (ref),
    constraint ck_books_ref check (ref ~ '^[A-Za-z0-9:_.-]{1,128}$')
);

insert into books (id, ref, kind) values (gen_random_uuid(), 'legacy:wallet', 'LEGACY');

-- ---------------------------------------------------------------- accounts
alter table accounts add column book_id uuid references books (id);
alter table accounts add column ref varchar(128);
update accounts
set book_id = (select id from books where ref = 'legacy:wallet'),
    ref     = lower(type) || ':' || id;
alter table accounts alter column book_id set not null;
alter table accounts alter column ref set not null;
alter table accounts add constraint ck_accounts_ref check (ref ~ '^[A-Za-z0-9:_.-]{1,128}$');
create unique index ux_accounts_book_ref_currency on accounts (book_id, ref, currency);

-- owner_id belonged to wallets; ownership now lives in the caller (core-service).
alter table accounts alter column owner_id drop not null;

alter table accounts drop constraint accounts_type_check;
alter table accounts add constraint ck_accounts_type
    check (type in ('ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE',
                    'USER', 'SYSTEM')); -- USER and SYSTEM: legacy wallet rows only

-- The ledger now RECORDS money (a card that is over its limit, an overdrawn bank
-- account); it no longer moves it, so new accounts may go negative.
alter table accounts alter column allow_negative set default true;

-- ---------------------------------------------------------------- entries
-- The append-only trigger rejects UPDATEs; switch it off just for this backfill.
alter table journal_entries disable trigger trg_journal_entries_append_only;

alter table journal_entries add column book_id uuid references books (id);
alter table journal_entries add column effective_date date;
update journal_entries
set book_id        = (select id from books where ref = 'legacy:wallet'),
    effective_date = (created_at at time zone 'UTC')::date;
alter table journal_entries alter column book_id set not null;
alter table journal_entries alter column effective_date set not null;

alter table journal_entries enable trigger trg_journal_entries_append_only;

-- What the caller says this entry is (EXPENSE, INCOME, TRANSFER, SPLIT, SETTLEMENT,
-- OPENING_BALANCE, REVERSAL...). The ledger doesn't interpret it.
alter table journal_entries drop constraint journal_entries_type_check;
alter table journal_entries alter column type type varchar(32);
alter table journal_entries add constraint ck_journal_entries_type check (type ~ '^[A-Z_]{1,32}$');

-- The caller's own id for what caused this entry (e.g. 'txn:<uuid>'), for tracing back.
alter table journal_entries add column source_ref varchar(128);

-- Edits and deletes are reversals: a new entry with negated postings that points
-- at the original. UNIQUE means an entry can be reversed at most once.
alter table journal_entries add column reverses_entry_id uuid references journal_entries (id);
alter table journal_entries add constraint ux_journal_entries_reverses unique (reverses_entry_id);

-- Period reports: "all entries of book X between two dates".
create index ix_journal_entries_book_date on journal_entries (book_id, effective_date);

-- ---------------------------------------------------------------- invariants
-- Rule 2, upgraded: an entry must balance IN EACH CURRENCY, and every posting
-- must hit an account of the entry's own book.
create or replace function ledger_check_entry_balanced() returns trigger
    language plpgsql as
$$
declare
    unbalanced record;
    foreign_postings int;
begin
    select currency, sum(amount) as total into unbalanced
    from postings
    where journal_entry_id = new.journal_entry_id
    group by currency
    having sum(amount) <> 0
    limit 1;

    if found then
        raise exception 'journal entry % is unbalanced: % postings sum to %',
            new.journal_entry_id, unbalanced.currency, unbalanced.total
            using errcode = 'check_violation';
    end if;

    select count(*) into foreign_postings
    from postings p
    join accounts a on a.id = p.account_id
    join journal_entries e on e.id = p.journal_entry_id
    where p.journal_entry_id = new.journal_entry_id
      and a.book_id <> e.book_id;

    if foreign_postings > 0 then
        raise exception 'journal entry % posts to an account of another book', new.journal_entry_id
            using errcode = 'check_violation';
    end if;
    return null;
end;
$$;
