-- =====================================================================
-- Transactional outbox (see docs/adr/0004-transactional-outbox.md)
--
-- A domain event is inserted here in the SAME transaction as the ledger
-- change it describes. Either both commit or neither does, so Kafka can
-- never hear about money that did not move, and no committed movement is
-- ever silently left unannounced.
--
-- The relay (OutboxRelay) publishes unpublished rows to Kafka in id order
-- and then sets published_at. Delivery is at-least-once: a crash between
-- "Kafka acked" and "published_at committed" re-sends the row, so
-- consumers de-duplicate on event_id.
-- =====================================================================

create table outbox_events (
    -- Monotonic, so the relay publishes in commit-ish order.
    id              bigint        generated always as identity primary key,
    event_id        uuid          not null,
    aggregate_type  varchar(32)   not null,
    -- Becomes the Kafka record key: all events of one aggregate go to the same partition, in order.
    aggregate_id    uuid          not null,
    event_type      varchar(64)   not null,
    payload         jsonb         not null,
    created_at      timestamptz   not null default now(),
    published_at    timestamptz,
    constraint ux_outbox_events_event_id unique (event_id)
);

-- The relay's only query: "oldest unpublished rows". A partial index stays tiny
-- because published rows drop out of it.
create index ix_outbox_events_unpublished on outbox_events (id) where published_at is null;
