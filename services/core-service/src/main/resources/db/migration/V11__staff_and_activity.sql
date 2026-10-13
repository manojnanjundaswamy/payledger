-- The admin console (docs/adr/0008-admin-console.md): PayLedger staff, and the activity it reports on.

-- A staff role per user. Everyone else is a customer with no admin access. The emails in ADMIN_EMAILS
-- are OWNER without a row here, so the first owner never depends on the database.
create table staff_roles (
    user_id     uuid          primary key references users (id),
    role        varchar(16)   not null check (role in ('OWNER', 'ADMIN', 'SUPPORT', 'ANALYST')),
    granted_by  uuid          references users (id),
    granted_at  timestamptz   not null default now()
);

-- One row per user per day they used the app (days in the business time zone, Asia/Kolkata by default).
-- Daily, weekly and monthly active users, and retention, are counts over this table. Nothing about what
-- the user did is stored: only that they came.
create table user_activity_days (
    user_id  uuid  not null references users (id),
    day      date  not null,
    primary key (day, user_id)
);

create index ix_user_activity_days_user on user_activity_days (user_id, day);

-- The latest day the user was active, for the users list.
alter table users add column last_active_on date;
