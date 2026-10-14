-- Bills and savings that record themselves on their due date (an EMI or SIP that always goes out), so the plan,
-- balances and safe to spend stay right without a tap. Only due dates from the day it was turned on are recorded.
alter table commitments add column auto boolean not null default false;
alter table commitments add column auto_since date;
