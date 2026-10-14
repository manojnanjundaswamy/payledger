-- Removes test data from the LOCAL core database (scripts/clean-test-data.sh runs it; never run it in production).
-- Keeps the user whose email is :'keep' and their own data; removes every other user with everything they own,
-- and the browser tests' "E2E …" leftovers (groups, accounts, categories, bills, notifications) from the kept account.
-- The script first reverses the kept account's E2E transactions through the API, so the ledger stays in step;
-- other users' ledger books are simply left behind (nothing reads them once the users are gone).
begin;

create temp table keep_user on commit drop as select id from users where lower(email) = lower(:'keep');
do $$ begin
  if (select count(*) from keep_user) <> 1 then raise exception 'the user to keep does not exist'; end if;
end $$;

create temp table gone_users on commit drop as select id from users where id not in (select id from keep_user);
create temp table gone_spaces on commit drop as
  select id from spaces where created_by in (select id from gone_users)
    and id not in (select space_id from space_members where user_id in (select id from keep_user));
create temp table gone_groups on commit drop as
  select id from groups where name ~* '^E2E\M' or created_by in (select id from gone_users);
create temp table kept_spaces on commit drop as select id from spaces where id not in (select id from gone_spaces);

-- Groups: the tests' groups go whole; in any other group a removed user becomes a named placeholder.
delete from group_expense_parts where expense_id in (select id from group_expenses where group_id in (select id from gone_groups));
delete from group_settlements where group_id in (select id from gone_groups);
delete from group_expenses where group_id in (select id from gone_groups);
delete from group_links where group_id in (select id from gone_groups);
delete from group_events where group_id in (select id from gone_groups);
delete from group_members where group_id in (select id from gone_groups);
delete from groups where id in (select id from gone_groups);
update group_members set user_id = null where user_id in (select id from gone_users);
update group_events set actor_user_id = null where actor_user_id in (select id from gone_users);
update group_members set added_by = (select id from keep_user) where added_by in (select id from gone_users);
update group_expenses set created_by = (select id from keep_user) where created_by in (select id from gone_users);
update group_settlements set created_by = (select id from keep_user) where created_by in (select id from gone_users);
update group_links set created_by = (select id from keep_user) where created_by in (select id from gone_users);
update groups set created_by = (select id from keep_user) where created_by in (select id from gone_users);

-- The kept account's E2E accounts, categories and bills (their transactions were reversed and deleted already).
create temp table test_accounts on commit drop as select id from money_accounts where space_id in (select id from kept_spaces) and name ~* '^E2E\M';
create temp table test_categories on commit drop as select id from categories where space_id in (select id from kept_spaces) and name ~* '^E2E\M';
delete from commitment_payments where commitment_id in (select id from commitments where account_id in (select id from test_accounts)
  or to_account_id in (select id from test_accounts) or category_id in (select id from test_categories) or name ~* '^E2E\M');
delete from commitments where account_id in (select id from test_accounts) or to_account_id in (select id from test_accounts)
  or category_id in (select id from test_categories) or (space_id in (select id from kept_spaces) and name ~* '^E2E\M');
-- Only deleted (reversed) transactions may still point at them; anything live stays, and so does its account.
delete from commitment_payments where transaction_id in (select id from transactions where deleted_at is not null
  and (account_id in (select id from test_accounts) or to_account_id in (select id from test_accounts) or category_id in (select id from test_categories)));
update captured_items set transaction_id = null where transaction_id in (select id from transactions where deleted_at is not null);
delete from transactions where deleted_at is not null
  and (account_id in (select id from test_accounts) or to_account_id in (select id from test_accounts) or category_id in (select id from test_categories));
delete from budget_limits where category_id in (select id from test_categories);
delete from tax_mappings where category_id in (select id from test_categories);
delete from group_entry_accounts where account_id in (select id from test_accounts);
update users set group_account_id = null where group_account_id in (select id from test_accounts);
delete from money_accounts where id in (select id from test_accounts)
  and not exists (select 1 from transactions t where t.account_id = money_accounts.id or t.to_account_id = money_accounts.id);
update categories set parent_id = null where parent_id in (select id from test_categories) and id not in (select id from test_categories);
delete from categories where id in (select id from test_categories) and parent_id is not null
  and not exists (select 1 from transactions t where t.category_id = categories.id);
delete from categories where id in (select id from test_categories)
  and not exists (select 1 from transactions t where t.category_id = categories.id)
  and not exists (select 1 from categories c where c.parent_id = categories.id);
delete from imports i where i.space_id in (select id from kept_spaces) and i.file_name ~* 'e2e'
  and not exists (select 1 from transactions t where t.import_id = i.id);

delete from captured_items where user_id in (select id from keep_user) and (merchant ~* '^E2E' or body ~* 'e2e');

-- Everything the other users owned.
delete from commitment_payments where commitment_id in (select id from commitments where space_id in (select id from gone_spaces));
delete from commitment_payments where transaction_id in (select id from transactions where space_id in (select id from gone_spaces));
delete from commitments where space_id in (select id from gone_spaces);
delete from captured_items where user_id in (select id from gone_users) or space_id in (select id from gone_spaces);
delete from capture_addresses where user_id in (select id from gone_users);
delete from transactions where space_id in (select id from gone_spaces);
delete from budget_limits where space_id in (select id from gone_spaces);
delete from tax_mappings where space_id in (select id from gone_spaces);
delete from imports where space_id in (select id from gone_spaces);
delete from group_entry_accounts where user_id in (select id from gone_users)
  or account_id in (select id from money_accounts where space_id in (select id from gone_spaces));
update users set group_account_id = null where id in (select id from gone_users);
delete from money_accounts where space_id in (select id from gone_spaces);
update categories set parent_id = null where space_id in (select id from gone_spaces);
delete from categories where space_id in (select id from gone_spaces);
delete from space_members where space_id in (select id from gone_spaces) or user_id in (select id from gone_users);
delete from outbox_events where aggregate_id in (select id from gone_spaces) and published_at is not null;
delete from spaces where id in (select id from gone_spaces);

-- Their traces: notifications (and the kept user's notifications about them or the tests), emails, activity.
delete from inbox where user_id in (select id from gone_users) or actor_id in (select id from gone_users)
  or (user_id in (select id from keep_user) and (title ~* 'E2E' or body ~* 'E2E'));
delete from email_outbox where user_id in (select id from gone_users);
delete from user_events where user_id in (select id from gone_users);
delete from user_activity_days where user_id in (select id from gone_users);
update request_log set user_id = null where user_id in (select id from gone_users);
delete from admin_audit where actor_id in (select id from gone_users);
update admin_audit set target_user_id = null where target_user_id in (select id from gone_users);
delete from staff_roles where user_id in (select id from gone_users);
update staff_roles set granted_by = (select id from keep_user) where granted_by in (select id from gone_users);
update app_settings set updated_by = (select id from keep_user) where updated_by in (select id from gone_users);
update budget_limits set updated_by = (select id from keep_user) where updated_by in (select id from gone_users);
update transactions set created_by = (select id from keep_user) where created_by in (select id from gone_users);
update money_accounts set created_by = (select id from keep_user) where created_by in (select id from gone_users);
update commitments set created_by = (select id from keep_user) where created_by in (select id from gone_users);
update imports set created_by = (select id from keep_user) where created_by in (select id from gone_users);
update spaces set created_by = (select id from keep_user) where created_by in (select id from gone_users);
delete from users where id in (select id from gone_users);

select (select count(*) from users) as users_left,
       (select count(*) from groups) as groups_left,
       (select count(*) from money_accounts where name ~* '^E2E\M') as e2e_accounts_left,
       (select count(*) from categories where name ~* '^E2E\M') as e2e_categories_left;
commit;
