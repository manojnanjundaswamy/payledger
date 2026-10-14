-- Entries for a goal: a transfer or income marked for a goal counts as saved towards it, an expense as spent on it;
-- a saving on the plan made for a goal marks each payment for it (docs/plan/ideas-2026-10.md, idea 6).
alter table transactions add column goal_id uuid references goals (id);
create index ix_transactions_goal on transactions (goal_id) where goal_id is not null and deleted_at is null;
alter table commitments add column goal_id uuid references goals (id);
