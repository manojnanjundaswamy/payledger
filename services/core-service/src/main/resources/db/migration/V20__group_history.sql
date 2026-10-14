-- A group's history, close to an audit log: who did what and when, with the details (what an expense was before and
-- after an edit, who paid, who it was for). The app writes the sentence; this keeps the facts. People see the history
-- of groups they're in. Amounts in details are paise.
create table group_events (
    id             uuid          primary key,
    group_id       uuid          not null references groups (id),
    actor_user_id  uuid          references users (id),
    -- The name at the time, so the history still reads after someone leaves or deletes their account.
    actor_name     varchar(100),
    action         varchar(30)   not null,
    details        jsonb         not null default '{}',
    at             timestamptz   not null default now()
);

create index ix_group_events_group on group_events (group_id, at desc, id desc);

-- What already happened, rebuilt from the rows that are there (marked backfilled: edits before today aren't known).
insert into group_events (id, group_id, actor_user_id, actor_name, action, details, at)
select gen_random_uuid(), g.id, g.created_by, coalesce(u.display_name, o.display_name), 'GROUP_CREATED',
       jsonb_build_object('name', g.name, 'backfilled', true), g.created_at
from groups g
left join users u on u.id = g.created_by
left join group_members o on o.group_id = g.id and o.role = 'OWNER';

insert into group_events (id, group_id, actor_user_id, actor_name, action, details, at)
select gen_random_uuid(), m.group_id, m.added_by, coalesce(u.display_name, a.display_name), 'MEMBER_ADDED',
       jsonb_build_object('member', m.display_name, 'backfilled', true), m.created_at
from group_members m
left join users u on u.id = m.added_by
left join group_members a on a.group_id = m.group_id and a.user_id = m.added_by
where m.role <> 'OWNER';

insert into group_events (id, group_id, actor_user_id, actor_name, action, details, at)
select gen_random_uuid(), m.group_id, m.user_id, m.display_name, 'MEMBER_LEFT',
       jsonb_build_object('member', m.display_name, 'backfilled', true), m.left_at
from group_members m where m.left_at is not null;

insert into group_events (id, group_id, actor_user_id, actor_name, action, details, at)
select gen_random_uuid(), e.group_id, e.created_by, coalesce(u.display_name, c.display_name), 'EXPENSE_ADDED',
       jsonb_build_object('expenseId', e.id, 'description', e.description, 'amount', e.amount, 'currency', e.currency,
                          'occurredOn', e.occurred_on, 'category', e.category, 'split', e.split, 'backfilled', true), e.created_at
from group_expenses e
left join users u on u.id = e.created_by
left join group_members c on c.group_id = e.group_id and c.user_id = e.created_by;

insert into group_events (id, group_id, actor_user_id, actor_name, action, details, at)
select gen_random_uuid(), e.group_id, null, null, 'EXPENSE_DELETED',
       jsonb_build_object('expenseId', e.id, 'description', e.description, 'amount', e.amount, 'currency', e.currency, 'backfilled', true), e.deleted_at
from group_expenses e where e.deleted_at is not null;

insert into group_events (id, group_id, actor_user_id, actor_name, action, details, at)
select gen_random_uuid(), s.group_id, s.created_by, coalesce(u.display_name, c.display_name), 'SETTLEMENT_RECORDED',
       jsonb_build_object('from', f.display_name, 'to', t.display_name, 'amount', s.amount, 'currency', s.currency,
                          'occurredOn', s.occurred_on, 'note', s.note, 'backfilled', true), s.created_at
from group_settlements s
join group_members f on f.id = s.from_member
join group_members t on t.id = s.to_member
left join users u on u.id = s.created_by
left join group_members c on c.group_id = s.group_id and c.user_id = s.created_by;
