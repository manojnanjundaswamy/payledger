-- Admin console, users (docs/adr/0008-admin-console.md).

-- A disabled user can't use the API (403 account-disabled) and can't sign in (disabled in Keycloak too).
-- Nothing is deleted: enabling them again brings everything back.
alter table users add column disabled_at timestamptz;

-- GRANTED: Premium given by staff until a date (a goodwill gesture, a partner, a contest prize).
alter table users drop constraint users_premium_source_check;
alter table users add constraint users_premium_source_check check (premium_source in ('TRIAL', 'PAID', 'GRANTED'));

-- Every admin write: who did what to whom, and when. Never updated or deleted.
create table admin_audit (
    id              uuid          primary key,
    actor_id        uuid          not null references users (id),
    action          varchar(40)   not null,
    target_user_id  uuid          references users (id),
    details         jsonb         not null default '{}'::jsonb,
    at              timestamptz   not null default now()
);

create index ix_admin_audit_at on admin_audit (at desc);
create index ix_admin_audit_target on admin_audit (target_user_id, at desc) where target_user_id is not null;
create index ix_users_created on users (created_at desc);
