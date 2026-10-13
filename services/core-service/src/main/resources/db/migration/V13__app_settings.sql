-- Settings staff change in the admin console (docs/adr/0008-admin-console.md): Free's limits, the trial length,
-- and an announcement shown to everyone. A missing key means the built-in default.
create table app_settings (
    key         varchar(40)   primary key,
    value       jsonb         not null,
    updated_by  uuid          references users (id),
    updated_at  timestamptz   not null default now()
);
