-- Where someone lives decides their currency, number format, time zone and financial year. Null country = not set up
-- yet: the app asks at sign-in until it is (existing users too: their defaults were India's).
alter table users add column country varchar(2);
alter table users add column fy_start_month smallint not null default 4 check (fy_start_month between 1 and 12);
