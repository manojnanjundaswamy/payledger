import { useMemo } from 'react'
import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { BarChart3 } from 'lucide-react'
import { Card, Skeleton } from '@/components/ui/card'
import { Input } from '@/components/ui/controls'
import { BUDGET_STATUS, GroupedBars } from '@/dashboard/charts'
import { cn } from '@/lib/cn'
import { formatMoneyShort, formatMoneyWhole } from '@/lib/money'
import { PERIOD_KEYS, financialYear, monthsIn, reportPeriod, type Period, type PeriodKey } from '@/lib/periods'
import { useBudgetMonths, useCategories, useCategoryTotals, type Category, type CategoryTotal } from '@/state/money'
import { useProfile } from '@/state/profile'
import { CategoryIcon, Select } from './parts'

const PERIOD_LABELS: Record<PeriodKey, string> = {
  'this-month': 'This month', 'last-month': 'Last month', 'this-fy': 'This financial year', 'last-fy': 'Last financial year',
  'last-12': 'Last 12 months', custom: 'Custom dates',
}

/** Where the money went and came from over a period, including India's April–March financial year. */
export function ReportsPage() {
  const search = useSearch({ from: '/app/reports' })
  const navigate = useNavigate({ from: '/reports' })
  const profile = useProfile().data
  const locale = profile?.settings.locale ?? 'en-IN'
  const period = reportPeriod(search.period ?? 'this-month', profile?.settings.budgetStartDay ?? 1, new Date(), search)
  const money = (minor: number) => formatMoneyWhole(minor, 'INR', locale)

  const spending = useCategoryTotals('EXPENSE', period.from, period.to)
  const income = useCategoryTotals('INCOME', period.from, period.to)
  const { data: categories = [] } = useCategories()

  const totalSpent = sum(spending.data)
  const totalIncome = sum(income.data)
  const net = totalIncome - totalSpent

  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-6">
      <div className="flex flex-wrap items-end gap-3">
        <div className="flex flex-col gap-1.5">
          <label htmlFor="report-period" className="text-sm font-medium">Period</label>
          <Select id="report-period" className="w-auto min-w-56" value={period.key}
            onChange={(e) => void navigate({ search: { period: e.target.value as PeriodKey, ...(e.target.value === 'custom' ? { from: period.from, to: period.to } : {}) } })}>
            {[...PERIOD_KEYS, 'custom' as const].map((k) => <option key={k} value={k}>{PERIOD_LABELS[k]}</option>)}
          </Select>
        </div>
        {period.key === 'custom' && (
          <>
            <div className="flex flex-col gap-1.5">
              <label htmlFor="report-from" className="text-sm font-medium">From</label>
              <Input id="report-from" type="date" value={period.from} onChange={(e) => e.target.value && void navigate({ search: (s) => ({ ...s, from: e.target.value }) })} />
            </div>
            <div className="flex flex-col gap-1.5">
              <label htmlFor="report-to" className="text-sm font-medium">To</label>
              <Input id="report-to" type="date" value={period.to} onChange={(e) => e.target.value && void navigate({ search: (s) => ({ ...s, to: e.target.value }) })} />
            </div>
          </>
        )}
        <p className="pb-2 text-sm text-muted" data-testid="report-range">
          {period.key.endsWith('fy') && <span className="font-medium text-fg">{financialYear(new Date(`${period.from}T00:00`)).label} · </span>}
          {range(period, locale)}
        </p>
      </div>

      {(spending.isError || income.isError) && (
        <Card className="p-pad text-sm text-muted">Reports are unavailable right now; they will be back shortly.</Card>
      )}

      <section aria-label="Summary" className="grid gap-3 sm:grid-cols-3">
        {[
          { label: 'Income', value: totalIncome, tone: '' },
          { label: 'Spending', value: totalSpent, tone: '' },
          { label: net >= 0 ? 'Saved' : 'Spent more than earned', value: Math.abs(net), tone: net >= 0 ? 'text-good-text' : 'text-critical' },
        ].map((x) => (
          <Card key={x.label} className="p-pad">
            <div className="text-xs text-muted">{x.label}</div>
            {spending.isPending || income.isPending
              ? <Skeleton className="mt-1 h-8 w-32" />
              : <div className={cn('text-2xl font-semibold tracking-tight tabular', x.tone)} data-testid={`report-${x.label.toLowerCase().replace(/ /g, '-')}`}>{money(x.value)}</div>}
            {x.label === 'Saved' && totalIncome > 0 && <div className="text-xs text-subtle">{Math.round((net / totalIncome) * 100)}% of income</div>}
          </Card>
        ))}
      </section>

      <SpendingByCategory totals={spending.data} pending={spending.isPending} categories={categories} period={period} money={money} />
      <MonthlyTrend period={period} locale={locale} />
      <BudgetAgainstActual period={period} locale={locale} money={money} />
    </div>
  )
}

/** Each top-level category with its subcategories folded in; a row opens its transactions. */
function SpendingByCategory({ totals, pending, categories, period, money }: {
  totals: CategoryTotal[] | undefined; pending: boolean; categories: Category[]; period: Period; money: (m: number) => string
}) {
  const rows = useMemo(() => {
    const byParent = new Map<string, number>()
    for (const t of totals ?? []) {
      const c = categories.find((x) => x.id === t.categoryId)
      const key = c?.parentId ?? t.categoryId
      byParent.set(key, (byParent.get(key) ?? 0) + t.amount)
    }
    return [...byParent.entries()].map(([id, amount]) => ({ category: categories.find((c) => c.id === id), id, amount }))
      .filter((r) => r.amount !== 0).sort((a, b) => b.amount - a.amount)
  }, [totals, categories])
  const total = rows.reduce((s, r) => s + r.amount, 0)
  const max = Math.max(...rows.map((r) => r.amount), 1)

  return (
    <section aria-label="Spending by category" className="flex flex-col gap-2">
      <h2 className="text-sm font-medium text-muted">Spending by category</h2>
      <Card className="p-0">
        {pending ? <Skeleton className="m-pad h-40" /> : rows.length === 0 ? (
          <div className="flex flex-col items-center gap-2 px-pad py-10 text-center text-sm text-muted">
            <BarChart3 className="size-6" />No spending in this period.
          </div>
        ) : (
          <ul className="divide-y divide-border">
            {rows.map((r) => (
              <li key={r.id}>
                <Link to="/transactions" search={{ category: r.id, from: period.from, to: period.to }}
                  className="flex items-center gap-3 px-pad py-2.5 hover:bg-surface-2" aria-label={`${r.category?.name ?? 'Category'}: ${money(r.amount)}, see transactions`}>
                  <CategoryIcon category={r.category} />
                  <span className="min-w-0 flex-1">
                    <span className="flex items-baseline justify-between gap-3 text-sm">
                      <span className="truncate font-medium">{r.category?.name ?? 'Category'}</span>
                      <span className="tabular shrink-0">{money(r.amount)} <span className="text-xs text-subtle">{Math.round((r.amount / total) * 100)}%</span></span>
                    </span>
                    <span className="mt-1 block h-1.5 overflow-hidden rounded-full bg-surface-2">
                      <span className="block h-full rounded-full" style={{ width: `${(r.amount / max) * 100}%`, background: `var(--${r.category?.color ?? 'series-8'})` }} />
                    </span>
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </section>
  )
}

/** Income against spending per calendar month, when the period spans more than one. */
function MonthlyTrend({ period, locale }: { period: Period; locale: string }) {
  const months = monthsIn(period.from, period.to)
  const multi = months.length > 1
  const income = useCategoryTotals('INCOME', period.from, period.to, true, multi)
  const spending = useCategoryTotals('EXPENSE', period.from, period.to, true, multi)
  if (!multi) return null
  const at = (rows: CategoryTotal[] | undefined, month: string) => (rows ?? []).filter((r) => r.month === month).reduce((s, r) => s + r.amount, 0)
  return (
    <section aria-label="Month by month" className="flex flex-col gap-2">
      <h2 className="text-sm font-medium text-muted">Month by month</h2>
      <Card className="p-pad">
        {income.isPending || spending.isPending ? <Skeleton className="h-48" /> : (
          <GroupedBars
            data={months.map((m) => {
              const [y, mo] = m.split('-').map(Number)
              return { label: new Date(y!, mo! - 1, 1).toLocaleDateString(locale, { month: 'short' }), values: [at(income.data, m), at(spending.data, m)] }
            })}
            series={[{ name: 'Income', slot: 1 }, { name: 'Spending', slot: 2 }]}
            format={(v) => formatMoneyWhole(v, 'INR', locale)}
            formatAxis={(v) => formatMoneyShort(v, 'INR', locale)}
          />
        )}
      </Card>
    </section>
  )
}

/** Each budget month in the period: what was budgeted against what those categories spent. */
function BudgetAgainstActual({ period, locale, money }: { period: Period; locale: string; money: (m: number) => string }) {
  // Budget months are named by the month they start in; a single budget month is the one the period starts in.
  const months = period.key === 'this-month' || period.key === 'last-month'
    ? [period.from.slice(0, 7)]
    : monthsIn(period.from, period.to).map((m) => m.slice(0, 7)).slice(-12)
  const results = useBudgetMonths(months)
  const rows = results.map((r, i) => {
    const budgets = r.data?.budgets ?? []
    return {
      month: months[i]!,
      pending: r.isPending,
      limit: budgets.reduce((s, b) => s + b.limit, 0),
      spent: budgets.reduce((s, b) => s + (b.spent ?? 0), 0),
      known: r.data?.spentKnown ?? false,
    }
  })
  if (rows.every((r) => !r.pending && r.limit === 0)) return null
  return (
    <section aria-label="Budget against actual" className="flex flex-col gap-2">
      <h2 className="text-sm font-medium text-muted">Budget against actual</h2>
      <Card className="divide-y divide-border p-0">
        {rows.filter((r) => r.pending || r.limit > 0).map((r) => {
          const [y, m] = r.month.split('-').map(Number)
          const status = r.spent > r.limit ? 'OVER' : r.spent * 5 >= r.limit * 4 ? 'CLOSE' : 'ON_TRACK'
          const look = BUDGET_STATUS[status]
          return (
            <div key={r.month} className="flex items-center gap-3 px-pad py-2.5 text-sm" data-testid={`budget-row-${r.month}`}>
              <span className="w-24 shrink-0 font-medium">{new Date(y!, m! - 1, 1).toLocaleDateString(locale, { month: 'short', year: 'numeric' })}</span>
              {r.pending ? <Skeleton className="h-5 flex-1" /> : (
                <>
                  <span className="flex-1 tabular text-muted">{r.known ? money(r.spent) : '—'} of {money(r.limit)}</span>
                  {r.known && (
                    <span className={cn('inline-flex items-center gap-1 text-xs', status === 'OVER' ? 'text-critical' : 'text-muted')}>
                      <look.icon className="size-3.5" style={{ color: look.color }} />
                      {status === 'OVER' ? `${money(r.spent - r.limit)} over` : `${money(r.limit - r.spent)} left`}
                    </span>
                  )}
                </>
              )}
            </div>
          )
        })}
      </Card>
    </section>
  )
}

function sum(rows: CategoryTotal[] | undefined): number {
  return (rows ?? []).reduce((s, r) => s + r.amount, 0)
}

function range(period: Period, locale: string): string {
  const fmt = (iso: string) => {
    const [y, m, d] = iso.split('-').map(Number)
    return new Date(y!, m! - 1, d!).toLocaleDateString(locale, { day: 'numeric', month: 'short', year: 'numeric' })
  }
  return `${fmt(period.from)} – ${fmt(period.to)}`
}
