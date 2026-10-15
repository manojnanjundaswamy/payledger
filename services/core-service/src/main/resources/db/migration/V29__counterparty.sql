-- Who an entry was with (optional): who paid you, whom you paid. Free text, so it can be someone not on PayLedger;
-- People and the filters match it by name.
alter table transactions add column counterparty varchar(80);
create index ix_transactions_counterparty on transactions (space_id, lower(counterparty)) where counterparty is not null and deleted_at is null;
