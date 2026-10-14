import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { CalendarPlus, ChevronLeft, ChevronRight, List, Plus, TrendingUp } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/card'
import { MonthCalendar, isoDay, menuItem, monthBounds } from '@/components/MonthCalendar'
import { api } from '@/lib/api'
import { formatMoneyShort } from '@/lib/money'
import { filterParams, useSpaceId, type Transaction, type TransactionFilter } from '@/state/money'
import type { TransactionsSearch } from './TransactionsPage'

/**
 * Transactions as a month calendar: each day shows what was spent (shaded by how much) and its items; a day's menu
 * opens that day's list or adds something on that date. The search and filters still apply.
 */
export function TransactionCalendar({ month, filter, locale, money, onOpen }: {
  /** yyyy-mm */
  month: string
  filter: TransactionFilter
  locale: string
  money: (minor: number, currency?: string) => string
  onOpen: (t: Transaction) => void
}) {
  const navigate = useNavigate({ from: '/transactions' })
  const spaceId = useSpaceId()
  const { start, end, prev, next } = monthBounds(month)
  // The month, narrowed further by any dates in the filter.
  const from = filter.from && filter.from > start ? filter.from : start
  const to = filter.to && filter.to < end ? filter.to : end
  const { data, isPending } = useQuery({
    queryKey: ['money', spaceId, 'transactions', 'calendar', { ...filter, from, to }],
    queryFn: async () => {
      // A month rarely has more than a few hundred; follow the pages to be sure nothing is left out.
      const all: Transaction[] = []
      let cursor: string | null = null
      do {
        const params = filterParams({ ...filter, from, to })
        params.set('limit', '200')
        if (cursor) params.set('cursor', cursor)
        const page: { items: Transaction[]; nextCursor: string | null } = await api(`/api/core/spaces/${spaceId}/transactions?${params}`)
        all.push(...page.items)
        cursor = page.nextCursor
      } while (cursor && all.length < 2000)
      return all
    },
    enabled: !!spaceId && from <= to,
  })

  const byDay = new Map<string, Transaction[]>()
  for (const t of data ?? []) byDay.set(t.occurredOn, [...(byDay.get(t.occurredOn) ?? []), t])
  const spentOn = (day: string) => (byDay.get(day) ?? []).filter((t) => t.kind === 'EXPENSE').reduce((s, t) => s + t.amount, 0)
  const earnedOn = (day: string) => (byDay.get(day) ?? []).filter((t) => t.kind === 'INCOME').reduce((s, t) => s + t.amount, 0)
  const spent = (data ?? []).filter((t) => t.kind === 'EXPENSE').reduce((s, t) => s + t.amount, 0)
  const earned = (data ?? []).filter((t) => t.kind === 'INCOME').reduce((s, t) => s + t.amount, 0)
  const [y, m] = month.split('-').map(Number)
  const go = (to: string) => void navigate({ search: (s: TransactionsSearch) => ({ ...s, month: to }), replace: true })
  const thisMonth = isoDay(new Date()).slice(0, 7)

  return (
    <section aria-label="Calendar" className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <Button variant="ghost" size="icon" aria-label="Previous month" onClick={() => go(prev)}><ChevronLeft /></Button>
        <h2 className="min-w-36 text-center font-semibold" data-testid="calendar-month">
          {new Date(y!, m! - 1, 1).toLocaleDateString(locale, { month: 'long', year: 'numeric' })}
        </h2>
        <Button variant="ghost" size="icon" aria-label="Next month" onClick={() => go(next)}><ChevronRight /></Button>
        {month !== thisMonth && <Button variant="ghost" size="sm" onClick={() => go(thisMonth)}>Today</Button>}
        <span className="flex-1" />
        <span className="text-sm text-muted tabular" data-testid="calendar-totals">
          <span className="text-[var(--flow-in)]">{money(earned)}</span> in · <span className="text-[var(--flow-out)]">{money(spent)}</span> spent
        </span>
      </div>
      {isPending ? <Skeleton className="h-96" /> : (
        <MonthCalendar start={start} end={end} locale={locale} label="Transactions calendar"
          flows={(day) => ({ in: earnedOn(day), out: spentOn(day) })} formatFlow={(m) => formatMoneyShort(m, 'INR', locale)}
          chips={(day) => (byDay.get(day) ?? []).map((t) => ({
            id: t.id,
            label: `${t.description} ${formatMoneyShort(t.amount, t.currency, locale)}`,
            title: `${t.description}: ${money(t.amount, t.currency)}`,
            tone: t.kind === 'INCOME' ? 'good' : 'default',
            onOpen: () => onOpen(t),
            menuLabel: <><span className="min-w-0 flex-1 truncate">Edit {t.description}</span><span className="pl-3 text-xs text-subtle tabular">{money(t.amount, t.currency)}</span></>,
          }))}
          actions={(date, past) => [
            ...(byDay.has(date) ? [{ key: 'list', label: '', asChild: (
              <Link to="/transactions" search={{ view: 'list', from: date, to: date }} className={menuItem}><List />See this day's transactions</Link>
            ) }] : []),
            ...(past ? [
              { key: 'expense', label: '', asChild: <Link to="/transactions" search={(s: TransactionsSearch) => ({ ...s, add: 'EXPENSE' as const, date })} className={menuItem}><Plus />Add an expense on this day</Link> },
              { key: 'income', label: '', asChild: <Link to="/transactions" search={(s: TransactionsSearch) => ({ ...s, add: 'INCOME' as const, date })} className={menuItem}><TrendingUp />Add income on this day</Link> },
            ] : []),
            { key: 'bill', label: '', asChild: <Link to="/plan" search={{ due: date }} className={menuItem}><CalendarPlus />Add a bill due this day</Link> },
          ]}
        />
      )}
    </section>
  )
}
