-- =====================================================================
-- PayLedger: double-entry ledger schema
--
-- Core rules (enforced by the DATABASE, not just the application):
--   1. Money is stored as BIGINT minor units (cents/paise). Never float.
--   2. Every journal entry's postings sum to exactly zero (money is never
--      created or destroyed, only moved).
--   3. Postings are append-only: no UPDATE, no DELETE. Corrections are
--      made with a new reversing entry, like accountants have done for 500 years.
--   4. User accounts can never go negative.
-- =====================================================================

create table accounts (
    id              uuid         primary key,
    owner_id        varchar(64)  not null,
    type            varchar(16)  not null check (type in ('USER', 'SYSTEM')),
    currency        varchar(3)   not null check (currency ~ '^[A-Z]{3}$'),
    -- Cached running balance = sum(postings.amount) for this account.
    -- Kept in the same transaction as the postings, so it is always consistent.
    balance         bigint       not null default 0,
    allow_negative  boolean      not null default false,
    -- Optimistic-lock version; bumped on every balance change.
    version         bigint       not null default 0,
    created_at      timestamptz  not null default now(),
    constraint ck_accounts_balance_non_negative check (allow_negative or balance >= 0)
);

create index ix_accounts_owner on accounts (owner_id);
-- Exactly one system "external funding" account per currency.
create unique index ux_accounts_system_currency on accounts (currency) where type = 'SYSTEM';

create table journal_entries (
    id               uuid          primary key,
    type             varchar(16)   not null check (type in ('DEPOSIT', 'TRANSFER', 'WITHDRAWAL', 'REVERSAL')),
    -- Client-supplied key; the UNIQUE constraint is the last line of defence
    -- against double-charging when two identical requests race each other.
    idempotency_key  varchar(128)  not null,
    -- SHA-256 of the request payload, so a reused key with a DIFFERENT
    -- payload is rejected instead of silently returning the old result.
    request_hash     varchar(64)   not null,
    description      varchar(255),
    created_at       timestamptz   not null default now(),
    constraint ux_journal_entries_idempotency_key unique (idempotency_key)
);

create table postings (
    id                bigint       generated always as identity primary key,
    journal_entry_id  uuid         not null references journal_entries (id),
    account_id        uuid         not null references accounts (id),
    -- Signed: positive = credit (balance goes up), negative = debit.
    amount            bigint       not null check (amount <> 0),
    currency          varchar(3)   not null,
    -- Snapshot of the account balance right after this posting:
    -- makes statements cheap and audits trivial.
    balance_after     bigint       not null,
    created_at        timestamptz  not null default now()
);

-- Statement queries: "latest postings for account X" (keyset pagination on id).
create index ix_postings_account_id_desc on postings (account_id, id desc);
create index ix_postings_journal_entry on postings (journal_entry_id);

-- ---------------------------------------------------------------------
-- Rule 2: an entry's postings must sum to zero.
-- A DEFERRABLE INITIALLY DEFERRED constraint trigger runs at COMMIT time,
-- after all postings of the entry have been inserted. A buggy application
-- release physically cannot commit an unbalanced entry.
-- ---------------------------------------------------------------------
create function ledger_check_entry_balanced() returns trigger
    language plpgsql as
$$
declare
    total bigint;
begin
    select coalesce(sum(amount), 0) into total
    from postings
    where journal_entry_id = new.journal_entry_id;

    if total <> 0 then
        raise exception 'journal entry % is unbalanced: postings sum to %', new.journal_entry_id, total
            using errcode = 'check_violation';
    end if;
    return null;
end;
$$;

create constraint trigger trg_postings_entry_balanced
    after insert on postings
    deferrable initially deferred
    for each row execute function ledger_check_entry_balanced();

-- ---------------------------------------------------------------------
-- Rule 3: the ledger is append-only.
-- ---------------------------------------------------------------------
create function ledger_reject_mutation() returns trigger
    language plpgsql as
$$
begin
    raise exception '% is append-only: % is not allowed', tg_table_name, tg_op
        using errcode = 'insufficient_privilege';
end;
$$;

create trigger trg_postings_append_only
    before update or delete on postings
    for each row execute function ledger_reject_mutation();

create trigger trg_journal_entries_append_only
    before update or delete on journal_entries
    for each row execute function ledger_reject_mutation();

-- ---------------------------------------------------------------------
-- Seed: one external-funding system account per supported currency.
-- Deposits debit this account (it goes negative = money owed to the
-- outside world) and credit the user's wallet.
-- ---------------------------------------------------------------------
insert into accounts (id, owner_id, type, currency, allow_negative)
values (gen_random_uuid(), 'system:external-funding', 'SYSTEM', 'USD', true),
       (gen_random_uuid(), 'system:external-funding', 'SYSTEM', 'EUR', true),
       (gen_random_uuid(), 'system:external-funding', 'SYSTEM', 'GBP', true),
       (gen_random_uuid(), 'system:external-funding', 'SYSTEM', 'INR', true);
