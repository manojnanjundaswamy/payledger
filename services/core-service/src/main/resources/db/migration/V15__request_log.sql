-- Every API request (docs/notifications.md, "Activity audit"): when to the millisecond, who, what route, the outcome,
-- how long it took and, for a failure, its error code and message. Never request or response bodies. Kept 90 days.
create table request_log (
    id            uuid          primary key,
    at            timestamptz   not null default clock_timestamp(),
    -- Also sent back as the X-Request-Id header and in error responses, so a user can quote it to support.
    request_id    varchar(64)   not null,
    user_id       uuid          references users (id),
    method        varchar(10)   not null,
    path          varchar(500)  not null,
    -- The route pattern ("/api/core/spaces/{spaceId}/transactions"): groups requests whatever their ids.
    route         varchar(200),
    status        smallint      not null,
    duration_ms   int           not null,
    ip            varchar(64),
    user_agent    varchar(300),
    error_code    varchar(80),
    error_detail  varchar(1000)
);

create index ix_request_log_at on request_log (at desc);
create index ix_request_log_user on request_log (user_id, at desc) where user_id is not null;
create index ix_request_log_errors on request_log (at desc) where status >= 400;
create index ix_request_log_request on request_log (request_id);

-- A self sign-up (the first visit after verifying the email) is an event of its own.
alter table user_events drop constraint user_events_type_check;
alter table user_events add constraint user_events_type_check check (type in ('SIGN_UP', 'SIGN_IN', 'CHANGE'));
