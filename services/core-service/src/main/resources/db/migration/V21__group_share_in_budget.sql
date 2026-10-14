-- Your share of group expenses in your own book (product plan 2.3). For each member with an account:
--   your share   -> an expense in your category, paid from your "Friends owe me" account (kind OWED);
--   what you paid -> a transfer from your bank (or card, cash) into "Friends owe me";
--   a settlement  -> a transfer between your bank and "Friends owe me".
-- So the bank shows the money gone, the budget counts only your share, and "Friends owe me" holds what others owe
-- you (negative: what you owe them).
alter table money_accounts drop constraint money_accounts_kind_check;
alter table money_accounts add constraint money_accounts_kind_check check (kind in ('BANK', 'CASH', 'CARD', 'WALLET', 'OWED'));

-- Which group entry a personal transaction books: "gexp:<expense id>:share" / ":paid", "gset:<settlement id>:out" / ":in".
alter table transactions add column group_link varchar(100);
create index ix_transactions_group_link on transactions (space_id, group_link) where group_link is not null and deleted_at is null;

-- Whether a user's share is booked, and the account their group payments come from by default.
alter table users add column group_budget boolean not null default true;
alter table users add column group_account_id uuid references money_accounts (id);
-- Group entries from before V21 are booked once, at the next start (GroupBooking.catchUp).
alter table users add column group_synced boolean not null default false;

-- The account a member said they paid an expense from (or a settlement from or into), kept for later edits.
create table group_entry_accounts (
    entry_id    uuid  not null,
    user_id     uuid  not null references users (id),
    account_id  uuid  not null references money_accounts (id),
    primary key (entry_id, user_id)
);
