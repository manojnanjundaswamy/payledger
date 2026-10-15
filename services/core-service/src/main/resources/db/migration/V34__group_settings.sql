-- The owner's switches (product plan 3.3): whether the join link works, whether people without an account may join as
-- guests, and whether members may change only what they added themselves.
alter table groups add column link_open boolean not null default true;
alter table groups add column guests_allowed boolean not null default true;
alter table groups add column own_edits_only boolean not null default false;
