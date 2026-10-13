import { useEffect, useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { ArrowLeftRight, FileUp, Plus, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, Skeleton } from '@/components/ui/card'
import { cn } from '@/lib/cn'
import { useAccounts, useCategories, useSpaceId, useTransactions, type Transaction, type TxnKind } from '@/state/money'
import { useProfile } from '@/state/profile'
import { CategoryIcon, Select, TXN_KINDS, dayLabel, useMoneyFormat } from './parts'
import { TransactionDialog } from './TransactionDialog'

export interface TransactionsSearch {
  add?: TxnKind
  /** A transaction to open for editing (a link from the dashboard or elsewhere). */
  open?: string
  account?: string
  category?: string
  from?: string
  to?: string
}

/** Every expense, income and transfer, newest first, grouped by day. */
export function TransactionsPage() {
  const search = useSearch({ from: '/app/transactions' })
  const navigate = useNavigate({ from: '/transactions' })
  const locale = useProfile().data?.settings.locale ?? 'en-IN'
  const money = useMoneyFormat()
  const { data: accounts, isPending: accountsPending } = useAccounts()
  const { data: categories = [] } = useCategories()
  const { data, isPending, isError, hasNextPage, fetchNextPage, isFetchingNextPage } = useTransactions({ accountId: search.account, categoryId: search.category, from: search.from, to: search.to })
  const [editing, setEditing] = useState<Transaction>()
  // ?open=<id>: open that transaction, even if it isn't on the first page of the list.
  const spaceId = useSpaceId()
  const opened = useQuery({
    queryKey: ['money', spaceId, 'transaction', search.open],
    queryFn: () => api<Transaction>(`/api/core/spaces/${spaceId}/transactions/${search.open}`),
    enabled: !!spaceId && !!search.open,
  })
  useEffect(() => {
    if (opened.data) setEditing(opened.data)
  }, [opened.data])

  const items = useMemo(() => data?.pages.flatMap((p) => p.items) ?? [], [data])
  const days = useMemo(() => {
    const byDay = new Map<string, Transaction[]>()
    for (const t of items) byDay.set(t.occurredOn, [...(byDay.get(t.occurredOn) ?? []), t])
    return [...byDay.entries()]
  }, [items])
  const categoryOf = (id: string | null) => categories.find((c) => c.id === id)
  const accountName = (id: string | null) => accounts?.find((a) => a.id === id)?.name ?? 'Archived account'

  const adding = search.add
  const setAdding = (kind?: TxnKind) => void navigate({ search: (s: TransactionsSearch) => ({ ...s, add: kind }), replace: true })

  if (!accountsPending && accounts?.length === 0) {
    return (
      <Card className="mx-auto flex max-w-xl flex-col items-center gap-3 px-pad py-12 text-center">
        <ArrowLeftRight className="size-8 text-muted" />
        <p className="font-medium">First, add an account</p>
        <p className="text-sm text-muted">Transactions are paid from an account: your bank, a card or cash.</p>
        <Button asChild variant="primary"><Link to="/accounts"><Plus />Add an account</Link></Button>
      </Card>
    )
  }

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-5">
      <div className="flex flex-wrap items-center gap-3">
        <Select aria-label="Filter by account" value={search.account ?? ''} className="w-auto min-w-48"
          onChange={(e) => void navigate({ search: (s: TransactionsSearch) => ({ ...s, account: e.target.value || undefined }) })}>
          <option value="">All accounts</option>
          {accounts?.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
        </Select>
        {(search.category || search.from || search.to) && (
          <span className="inline-flex items-center gap-1.5 rounded-full border border-border bg-surface-2 py-1 pr-1 pl-3 text-xs" data-testid="filter-chip">
            {search.category && (
              <Link to="/categories" className="hover:underline hover:underline-offset-4" title="Categories">{categoryOf(search.category)?.name ?? 'Category'}</Link>
            )}
            {search.category && search.from && search.to && ' · '}
            {search.from && search.to && `${dayLabel(search.from, locale)} – ${dayLabel(search.to, locale)}`}
            <button type="button" aria-label="Clear filter" className="rounded-full p-0.5 text-muted hover:bg-surface-3 hover:text-fg"
              onClick={() => void navigate({ search: (s: TransactionsSearch) => ({ ...s, category: undefined, from: undefined, to: undefined }) })}>
              <X className="size-3.5" />
            </button>
          </span>
        )}
        <span className="flex-1" />
        <Button asChild variant="ghost"><Link to="/import"><FileUp />Import</Link></Button>
        <Button variant="outline" onClick={() => setAdding('TRANSFER')}><ArrowLeftRight />Transfer</Button>
        <Button variant="primary" onClick={() => setAdding('EXPENSE')}><Plus />Add</Button>
      </div>

      {isError && <Card className="p-pad text-sm text-critical">Couldn't load transactions. Refresh to try again.</Card>}
      {isPending && <div className="flex flex-col gap-2">{Array.from({ length: 5 }, (_, i) => <Skeleton key={i} className="h-14" />)}</div>}

      {!isPending && items.length === 0 && (
        <Card className="flex flex-col items-center gap-3 px-pad py-12 text-center">
          <p className="font-medium">Nothing here yet</p>
          <p className="max-w-sm text-sm text-muted">Add today's spending as it happens: amount, category, done. It takes a few seconds.</p>
          <Button variant="primary" onClick={() => setAdding('EXPENSE')}><Plus />Add an expense</Button>
        </Card>
      )}

      {days.map(([day, rows]) => (
        <section key={day} aria-label={dayLabel(day, locale)} className="flex flex-col gap-1.5">
          <h2 className="px-1 text-xs font-medium tracking-wide text-muted uppercase">{dayLabel(day, locale)}</h2>
          <Card className="divide-y divide-border p-0">
            {rows.map((t) => {
              const category = categoryOf(t.categoryId)
              const sign = t.kind === 'INCOME' ? 1 : t.kind === 'EXPENSE' ? -1 : 0
              return (
                <button key={t.id} type="button" onClick={() => setEditing(t)} aria-label={`Edit ${t.description}`}
                  className="flex w-full items-center gap-3 px-pad py-2.5 text-left hover:bg-surface-2 first:rounded-t-card last:rounded-b-card">
                  {t.kind === 'TRANSFER'
                    ? <span className="flex size-8 shrink-0 items-center justify-center rounded-full bg-surface-2 text-muted"><ArrowLeftRight className="size-4" /></span>
                    : <CategoryIcon category={category} />}
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-medium">{t.description}</span>
                    <span className="block truncate text-xs text-subtle">
                      {t.kind === 'TRANSFER'
                        ? `${accountName(t.accountId)} → ${accountName(t.toAccountId)}`
                        : `${category?.name ?? 'Category'} · ${accountName(t.accountId)}`}
                    </span>
                  </span>
                  <span className={cn('shrink-0 text-sm font-medium tabular', sign > 0 && 'text-good-text', sign === 0 && 'text-muted')}>
                    {money(sign * t.amount || t.amount, t.currency, { sign: sign !== 0 })}
                  </span>
                </button>
              )
            })}
          </Card>
        </section>
      ))}

      {hasNextPage && (
        <Button variant="outline" onClick={() => void fetchNextPage()} disabled={isFetchingNextPage} className="self-center">
          {isFetchingNextPage ? 'Loading…' : 'Show older'}
        </Button>
      )}

      <TransactionDialog
        open={!!adding || !!editing}
        onOpenChange={(o) => {
          if (o) return
          setEditing(undefined)
          if (adding) setAdding(undefined)
          if (search.open) void navigate({ search: (s: TransactionsSearch) => ({ ...s, open: undefined }), replace: true })
        }}
        transaction={editing}
        initialKind={adding ?? 'EXPENSE'}
        initialAccountId={search.account}
      />
    </div>
  )
}

export const TXN_KIND_VALUES = Object.keys(TXN_KINDS) as TxnKind[]
