-- Rollover (envelope budgeting, from the market scan: Goodbudget): what a budget didn't spend carries into
-- the next month, and an overspend comes off it. Like the limit, it is set from a month onwards.
alter table budget_limits add column rollover boolean not null default false;
