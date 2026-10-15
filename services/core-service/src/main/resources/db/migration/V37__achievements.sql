-- Badges earned (ProgressService): once each, with when. Streaks and challenges are worked out from entries.
create table achievements (
    user_id      uuid not null references users (id) on delete cascade,
    key          varchar(40) not null,
    unlocked_at  timestamptz not null default now(),
    primary key (user_id, key)
);
