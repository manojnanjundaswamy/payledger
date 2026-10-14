-- People and lending (product plan 2.1 "Lending (udhaar)", 2.2 "one-to-one expenses outside groups").
-- A one-to-one ledger between you and one person is a group of two marked direct: it isn't listed with your groups,
-- the People page shows it instead. Everything else (balances, settling up, history, your book) is the group's.
alter table groups add column direct boolean not null default false;

-- A loan: money handed over, not shared spending. For the borrower it's money in (owed back), never spending.
alter table group_expenses add column loan boolean not null default false;
-- When a loan should be paid back by: a reminder to both on the day, and a nudge after.
alter table group_expenses add column due_on date;
create index ix_group_expenses_due on group_expenses (due_on) where due_on is not null and deleted_at is null;
