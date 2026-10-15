-- Gmail never tells us forwarding was confirmed; it's on once a forwarded email arrives for the current address, or
-- when the person says so. address_since: when the current address was made (a new one starts again).
alter table capture_addresses add column forwarding_confirmed_at timestamptz;
alter table capture_addresses add column address_since timestamptz not null default now();
