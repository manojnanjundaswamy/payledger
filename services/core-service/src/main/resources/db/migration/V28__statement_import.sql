-- Bank statements (CSV, Excel, PDF) through auto-capture's review list: each row is an item like an alert's, with
-- the account it came from (chosen once for the file) and which upload it belongs to.
alter table captured_items alter column source type varchar(12);
alter table captured_items drop constraint captured_items_source_check;
alter table captured_items add constraint captured_items_source_check check (source in ('EMAIL', 'PASTE', 'SHARE', 'STATEMENT'));
alter table captured_items add column account_id uuid references money_accounts (id);
alter table captured_items add column batch varchar(120);
