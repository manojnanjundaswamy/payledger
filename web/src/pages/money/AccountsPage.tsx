import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { Plus, Wallet } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge, Card, Skeleton } from '@/components/ui/card'
import { Switch } from '@/components/ui/controls'
import { cn } from '@/lib/cn'
import { useAccounts, type Account } from '@/state/money'
import { AccountDialog } from './AccountDialog'
import { NetWorthChart } from './NetWorthChart'
import { ACCOUNT_KINDS, EditButton, useMoneyFormat } from './parts'

/** Bank accounts, cash, cards and wallets with balances from the ledger. */
export function AccountsPage() {
  const [showArchived, setShowArchived] = useState(false)
  const { data: accounts, isPending, isError } = useAccounts(showArchived)
  const money = useMoneyFormat()
  const [editing, setEditing] = useState<Account | 'new' | null>(null)

  const open = (accounts ?? []).filter((a) => !a.archived)
  const known = open.every((a) => a.balance !== null)
  // Net worth in the main currency: what you have minus what you owe on cards.
  const netWorth = open.filter((a) => a.currency === 'INR')
    .reduce((sum, a) => sum + (a.kind === 'CARD' ? -(a.balance ?? 0) : (a.balance ?? 0)), 0)
  const groups: { title: string; rows: Account[] }[] = [
    { title: 'Money you have', rows: (accounts ?? []).filter((a) => a.kind !== 'CARD' && !a.archived) },
    { title: 'Cards', rows: (accounts ?? []).filter((a) => a.kind === 'CARD' && !a.archived) },
    { title: 'Archived', rows: (accounts ?? []).filter((a) => a.archived) },
  ]

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
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
          <Card className="divide-y divide-border p-0">
            {g.rows.map((a) => {
              const { icon: Icon, label } = ACCOUNT_KINDS[a.kind]
              return (
                <div key={a.id} className="flex items-center gap-1 pr-2 hover:bg-surface-2 first:rounded-t-card last:rounded-b-card">
                <Link to="/transactions" search={{ account: a.id }} aria-label={`${a.name} transactions`}
                  className="flex min-w-0 flex-1 items-center gap-3 py-3 pl-pad text-left">
                  <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-surface-2 text-muted"><Icon className="size-4" /></span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-medium">{a.name}</span>
                    <span className="block text-xs text-subtle">{label}{a.currency !== 'INR' && ` · ${a.currency}`}</span>
                  </span>
                  <span className="text-right">
                    <span className={cn('block font-medium tabular', a.balance !== null && a.balance < 0 && 'text-critical')} data-testid={`balance-${a.name}`}>
                      {a.balance === null ? '—' : money(a.balance, a.currency)}
                    </span>
                    {a.kind === 'CARD' && a.balance !== null && <span className="block text-xs text-subtle">owed</span>}
                  </span>
                  {a.archived && <Badge>Archived</Badge>}
                </Link>
                <EditButton label={`Edit ${a.name}`} onClick={() => setEditing(a)} />
                </div>
              )
            })}
          </Card>
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
