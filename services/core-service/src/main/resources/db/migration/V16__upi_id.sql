-- The UPI id (VPA) people can pay this user at, like "asha@okhdfcbank". Optional; shown only to people they share a
-- group with, so a friend who owes them can pay in one tap (docs/plan/ideas-2026-10.md, idea 4).
alter table users add column upi_id varchar(100) check (upi_id ~ '^[a-zA-Z0-9._-]{2,64}@[a-zA-Z][a-zA-Z0-9]{1,63}$');
