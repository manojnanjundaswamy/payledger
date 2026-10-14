import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { ArrowLeftRight, Goal as GoalIcon, Plus, Receipt, Wallet } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge, Card, Skeleton } from '@/components/ui/card'
import { Switch } from '@/components/ui/controls'
import { cn } from '@/lib/cn'
import { formatMoneyShort } from '@/lib/money'
import { useGoals } from '@/state/goals'
import { useAccounts, useBreakdown, type Account } from '@/state/money'
import { useProfile } from '@/state/profile'
import { AccountDialog } from './AccountDialog'
import { NetWorthChart } from './NetWorthChart'
import { ACCOUNT_KINDS, EditButton, dayLabel, useMoneyFormat } from './parts'

/** First day of the month n months ago, and the last day of this month, as yyyy-mm-dd. */
function monthsBack(n: number): { from: string; to: string; months: string[] } {
  const now = new Date()
  const pad = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
  const months = Array.from({ length: n }, (_, i) => pad(new Date(now.getFullYear(), now.getMonth() - (n - 1) + i, 1)))
  const last = new Date(now.getFullYear(), now.getMonth() + 1, 0)
  return { from: `${months[0]}-01`, to: `${pad(last)}-${String(last.getDate()).padStart(2, '0')}`, months }
}

/**
 * Bank accounts, cash, cards and wallets: what you have and owe at a glance, then each account with its balance,
 * this month's money in and out, six months of it, the latest entry, and the things you do with an account next.
 */
export function AccountsPage() {
  const [showArchived, setShowArchived] = useState(false)
  const { data: accounts, isPending, isError } = useAccounts(showArchived)
  const money = useMoneyFormat()
  const [editing, setEditing] = useState<Account | 'new' | null>(null)
  const range = monthsBack(6)
  const thisMonth = range.months[range.months.length - 1]!
  const { data: all } = useBreakdown({ from: `${thisMonth}-01`, to: range.to })

  const open = (accounts ?? []).filter((a) => !a.archived)
  const known = open.every((a) => a.balance !== null)
  const inr = open.filter((a) => a.currency === 'INR')
  // What friends owe you is yours too (net worth), and below zero it's what you owe them.
  const have = inr.filter((a) => a.kind !== 'CARD').reduce((s, a) => s + (a.balance ?? 0), 0)
  const owe = inr.filter((a) => a.kind === 'CARD').reduce((s, a) => s + (a.balance ?? 0), 0)
  // Net worth in the main currency: what you have minus what you owe on cards.
  const netWorth = have - owe
  const groups: { title: string; rows: Account[] }[] = [
    { title: 'Money you have', rows: (accounts ?? []).filter((a) => a.kind !== 'CARD' && a.kind !== 'OWED' && !a.archived) },
    { title: 'Friends & groups', rows: (accounts ?? []).filter((a) => a.kind === 'OWED' && !a.archived) },
    { title: 'Cards', rows: (accounts ?? []).filter((a) => a.kind === 'CARD' && !a.archived) },
    { title: 'Archived', rows: (accounts ?? []).filter((a) => a.archived) },
  ]

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="text-sm text-muted">Net worth</p>
          <div className="text-3xl font-semibold tracking-tight tabular" data-testid="net-worth">
            {isPending ? <Skeleton className="h-9 w-40" /> : known ? money(netWorth) : '—'}
          </div>
          {!isPending && !known && <p className="text-xs text-subtle">Balances are unavailable right now; they will be back shortly.</p>}
        </div>
        <Button variant="primary" onClick={() => setEditing('new')}><Plus />Add account</Button>
      </div>

      {open.length > 0 && known && (
        <section aria-label="At a glance" className="grid grid-cols-2 gap-3 lg:grid-cols-4">
          {[
            { label: 'In your accounts', value: money(have), sub: `${inr.filter((a) => a.kind !== 'CARD').length} accounts`, tone: '' },
            { label: 'Owed on cards', value: money(owe), sub: owe > 0 ? 'pay before the due date' : 'nothing owed', tone: owe > 0 ? 'text-critical' : '' },
            { label: 'In this month', value: money(all?.earned ?? 0), sub: 'income', tone: 'text-[var(--flow-in)]' },
            { label: 'Out this month', value: money(all?.spent ?? 0), sub: 'spending', tone: 'text-[var(--flow-out)]' },
          ].map((k) => (
            <Card key={k.label} className="p-pad">
              <div className="text-xs text-muted">{k.label}</div>
              <div className={cn('text-xl font-semibold tracking-tight tabular', k.tone)}>{k.value}</div>
              <div className="text-xs text-subtle">{k.sub}</div>
            </Card>
          ))}
        </section>
      )}

      {open.length > 0 && known && <Card className="p-pad"><NetWorthChart /></Card>}

      {isError && <Card className="p-pad text-sm text-critical">Couldn't load your accounts. Refresh to try again.</Card>}

      {!isPending && open.length === 0 && !showArchived && (
        <Card className="flex flex-col items-center gap-3 px-pad py-12 text-center">
          <Wallet className="size-8 text-muted" />
          <p className="font-medium">Add your first account</p>
          <p className="max-w-sm text-sm text-muted">Your bank account, a credit card or the cash in your wallet. Start with today's balance; history can come later.</p>
          <Button variant="primary" onClick={() => setEditing('new')}><Plus />Add account</Button>
        </Card>
      )}

      {groups.filter((g) => g.rows.length > 0).map((g) => (
        <section key={g.title} aria-label={g.title} className="flex flex-col gap-2">
          <h2 className="text-sm font-medium text-muted">{g.title}</h2>
          <div className="grid gap-3 md:grid-cols-2">
            {g.rows.map((a) => <AccountCard key={a.id} account={a} range={range} onEdit={() => setEditing(a)} />)}
          </div>
        </section>
      ))}

      <div className="flex items-center justify-between gap-4">
        <Link to="/transactions" className="text-sm text-muted underline underline-offset-4 hover:text-fg">See all transactions</Link>
        <label className="flex items-center gap-2 text-sm text-muted">
          <Switch checked={showArchived} onCheckedChange={setShowArchived} label="Show archived accounts" />
          Show archived
        </label>
      </div>

      <AccountDialog open={editing !== null} onOpenChange={(o) => !o && setEditing(null)} account={editing === 'new' || editing === null ? undefined : editing} />
    </div>
  )
}

function AccountCard({ account: a, range, onEdit }: { account: Account; range: ReturnType<typeof monthsBack>; onEdit: () => void }) {
  const money = useMoneyFormat()
  const locale = useProfile().data?.settings.locale ?? 'en-IN'
  const { data } = useBreakdown({ accountId: a.id, from: range.from, to: range.to })
  const { data: goals = [] } = useGoals()
  const goal = goals.find((g) => g.accountId === a.id)
  const { icon: Icon, label } = ACCOUNT_KINDS[a.kind]
  const at = (m: string) => data?.byMonth.find((x) => x.month === m)
  const thisMonth = at(range.months[range.months.length - 1]!)
  const max = Math.max(1, ...range.months.map((m) => Math.max(at(m)?.earned ?? 0, at(m)?.spent ?? 0)))
  const latest = data?.recent[0]

  return (
    <Card className={cn('flex flex-col gap-3 p-pad', a.archived && 'opacity-70')} data-testid={`account-${a.name}`}>
      <div className="flex items-start gap-3">
        <Link to="/transactions" search={{ account: a.id }} aria-label={`${a.name} transactions`} className="flex min-w-0 flex-1 items-start gap-3 hover:underline-offset-4">
          <span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-surface-2 text-muted"><Icon className="size-5" /></span>
          <span className="min-w-0 flex-1">
            <span className="block truncate font-medium">{a.name}</span>
            <span className="block text-xs text-subtle">{label}{a.currency !== 'INR' && ` · ${a.currency}`}</span>
          </span>
        </Link>
        <span className="text-right">
          <span className={cn('block text-lg font-semibold tabular', a.balance !== null && a.balance < 0 && 'text-critical')} data-testid={`balance-${a.name}`}>
            {a.balance === null ? '—' : money(a.balance, a.currency)}
          </span>
          {a.kind === 'CARD' && a.balance !== null && <span className="block text-xs text-subtle">owed</span>}
          {a.kind === 'OWED' && a.balance !== null && <span className="block text-xs text-subtle">{a.balance >= 0 ? 'owed to you' : 'you owe'}</span>}
        </span>
        {a.archived && <Badge>Archived</Badge>}
        <EditButton label={`Edit ${a.name}`} onClick={onEdit} />
      </div>

      {/* Six months of money in (green) and out (blue); this month on the right. */}
      <div className="flex items-end gap-3">
        <div className="flex h-10 flex-1 items-end gap-1" role="img" aria-label={`Money in and out over six months for ${a.name}`}>
          {range.months.map((m) => (
            <span key={m} className="flex h-full flex-1 items-end gap-px" title={`${m}: in ${money(at(m)?.earned ?? 0)}, out ${money(at(m)?.spent ?? 0)}`}>
              <span className="flex-1 rounded-t-sm bg-[var(--flow-in)]" style={{ height: `${((at(m)?.earned ?? 0) / max) * 100}%`, minHeight: at(m)?.earned ? 2 : 0 }} />
              <span className="flex-1 rounded-t-sm bg-[var(--flow-out)]" style={{ height: `${((at(m)?.spent ?? 0) / max) * 100}%`, minHeight: at(m)?.spent ? 2 : 0 }} />
            </span>
          ))}
        </div>
        <div className="text-right text-xs tabular" data-testid="account-month">
          <div className="text-[var(--flow-in)]">+{formatMoneyShort(thisMonth?.earned ?? 0, a.currency, locale)}</div>
          <div className="text-[var(--flow-out)]">−{formatMoneyShort(thisMonth?.spent ?? 0, a.currency, locale)}</div>
          <div className="text-subtle">this month</div>
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted">
        {latest
          ? <Link to="/transactions" search={{ open: latest.id }} className="min-w-0 truncate hover:text-fg hover:underline">Latest: {latest.description} · {money(latest.amount, a.currency)} · {dayLabel(latest.occurredOn, locale)}</Link>
          : <span>No entries yet</span>}
        {goal && <Link to="/goals" className="flex items-center gap-1 hover:text-fg hover:underline"><GoalIcon className="size-3.5" />Saving for {goal.name}</Link>}
      </div>

      {a.kind === 'OWED' && (
        <p className="text-xs text-muted">Your share of group expenses counts as spending; what you paid for others waits here until they pay you back. <Link to="/groups" className="text-fg underline underline-offset-4">Your groups</Link></p>
      )}
      {!a.archived && a.kind !== 'OWED' && (
        <div className="flex flex-wrap gap-1 border-t border-border pt-3">
          <Button asChild size="sm" variant="ghost"><Link to="/transactions" search={{ add: 'EXPENSE', account: a.id }}><Receipt />{a.kind === 'CARD' ? 'Add a purchase' : 'Add expense'}</Link></Button>
          <Button asChild size="sm" variant="ghost"><Link to="/transactions" search={{ add: 'TRANSFER', account: a.id }}><ArrowLeftRight />{a.kind === 'CARD' ? 'Pay the card' : 'Transfer'}</Link></Button>
          <Button asChild size="sm" variant="ghost"><Link to="/transactions" search={{ account: a.id }}>See all</Link></Button>
        </div>
      )}
    </Card>
  )
}
