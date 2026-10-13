-- Plans (docs/plan/market-and-pricing.md): Free, or Premium until a date (a trial or, later, a paid
-- subscription). A space's plan is its owner's, so a household (Phase 5) is covered by one subscription.
alter table users add column premium_until timestamptz;
-- TRIAL or PAID: what gave Premium, for the plan screen and support.
alter table users add column premium_source varchar(16) check (premium_source in ('TRIAL', 'PAID'));
-- The 30-day trial is once per user.
alter table users add column trial_started_at timestamptz;
