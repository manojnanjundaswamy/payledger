import { useEffect, useMemo, useState } from 'react'
import { SearchSelect } from '@/components/ui/search-select'
import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { BarChart3, Download, FileSpreadsheet, Printer, Search, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, Skeleton } from '@/components/ui/card'
import { Input, Segmented } from '@/components/ui/controls'
import { MonthCalendar } from '@/components/MonthCalendar'
import { BUDGET_STATUS, BarList, GroupedBars } from '@/dashboard/charts'
import { cn } from '@/lib/cn'
import { formatMoneyShort, formatMoneyWhole } from '@/lib/money'
import { PERIOD_KEYS, financialYear, monthsIn, reportPeriod, type Period, type PeriodKey } from '@/lib/periods'
import {
  exportUrl, useBreakdown, type Account, type Breakdown, useAccounts, useBudgetMonths, useCategories, useSpaceId, type Category, type TransactionFilter,
} from '@/state/money'
import { useProfile } from '@/state/profile'
import { CategoryIcon, Select, accountOptions, categoryOptions } from './parts'

const PERIOD_LABELS: Record<PeriodKey, string> = {
  'this-month': 'This month', 'last-month': 'Last month', 'this-fy': 'This financial year', 'last-fy': 'Last financial year',
  'last-12': 'Last 12 months', custom: 'Custom dates',
}

/** The report's search, as it sits in the URL. */
export interface ReportsSearch {
  period?: PeriodKey
  from?: string
  to?: string
  account?: string
  category?: string
  tag?: string
  q?: string
}

/**
 * Where the money went and came from over a period (including India's April–March financial year), narrowed by
 * account, category, tag or words. Every chart opens the transactions behind it, and the report downloads as the
 * transactions (CSV, Excel), a summary, or a printout.
 */
export function ReportsPage() {
  const search = useSearch({ from: '/app/reports' }) as ReportsSearch
  const navigate = useNavigate({ from: '/reports' })
  const profile = useProfile().data
  const locale = profile?.settings.locale ?? 'en-IN'
  const fyStart = profile?.settings.fyStartMonth ?? 4
  const period = reportPeriod(search.period ?? 'this-month', profile?.settings.budgetStartDay ?? 1, new Date(), search, fyStart)
  const money = (minor: number) => formatMoneyWhole(minor, undefined, locale)
  const spaceId = useSpaceId()
  const { data: categories = [] } = useCategories()
  const { data: accounts = [] } = useAccounts(true)
  const filter: TransactionFilter = { from: period.from, to: period.to, accountId: search.account, categoryId: search.category, tag: search.tag, q: search.q }
  const { data, isPending, isError } = useBreakdown(filter)
  const set = (next: Partial<ReportsSearch>) => void navigate({ search: (s: ReportsSearch) => ({ ...s, ...next }), replace: true })
  const narrowed = !!(search.account || search.category || search.tag || search.q)
  // What a chart opens: the transactions behind it, with the report's own filters kept.
  const txnSearch = { account: search.account, category: search.category, tag: search.tag, q: search.q, from: period.from, to: period.to }

  const net = (data?.earned ?? 0) - (data?.spent ?? 0)

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6 print:max-w-none">
      <div className="flex flex-wrap items-end gap-3 print:hidden">
        <div className="flex flex-col gap-1.5">
          <label htmlFor="report-period" className="text-sm font-medium">Period</label>
          <Select id="report-period" className="w-auto min-w-56" value={period.key}
            onChange={(e) => set({ period: e.target.value as PeriodKey, ...(e.target.value === 'custom' ? { from: period.from, to: period.to } : { from: undefined, to: undefined }) })}>
            {[...PERIOD_KEYS, 'custom' as const].map((k) => <option key={k} value={k}>{PERIOD_LABELS[k]}</option>)}
          </Select>
        </div>
        {period.key === 'custom' && (
          <>
            <div className="flex flex-col gap-1.5">
              <label htmlFor="report-from" className="text-sm font-medium">From</label>
              <Input id="report-from" type="date" value={period.from} onChange={(e) => e.target.value && set({ from: e.target.value })} />
            </div>
            <div className="flex flex-col gap-1.5">
              <label htmlFor="report-to" className="text-sm font-medium">To</label>
              <Input id="report-to" type="date" value={period.to} onChange={(e) => e.target.value && set({ to: e.target.value })} />
            </div>
          </>
        )}
        {spaceId && (
          <div className="ml-auto flex flex-wrap items-center gap-1 pb-1" role="group" aria-label="Download">
            <Button asChild variant="ghost" size="sm"><a href={exportUrl(spaceId, 'xlsx', filter)} download><Download />Excel</a></Button>
            <Button asChild variant="ghost" size="sm"><Link to="/review">Month in review</Link></Button>
            {(profile?.settings.country ?? 'IN') === 'IN' && <Button asChild variant="ghost" size="sm"><Link to="/tax">Tax (80C, 80D…)</Link></Button>}
            <Button asChild variant="ghost" size="sm"><a href={exportUrl(spaceId, 'csv', filter)} download><Download />CSV</a></Button>
            <Button variant="ghost" size="sm" disabled={!data} aria-label="Download the summary"
              onClick={() => data && downloadSummary(data, categories, accounts, period, locale)}><FileSpreadsheet />Summary</Button>
            <Button variant="ghost" size="sm" onClick={() => window.print()} aria-label="Print or save as PDF"><Printer />Print</Button>
          </div>
        )}
      </div>

      <ReportFilters search={search} set={set} categories={categories} accounts={accounts} />

      <p className="-mt-3 text-sm text-muted" data-testid="report-range">
        {period.key.endsWith('fy') && <span className="font-medium text-fg">{financialYear(new Date(`${period.from}T00:00`), fyStart).label} · </span>}
        {range(period, locale)}
        {narrowed && <span> · narrowed by the filters above</span>}
      </p>

      {isError && <Card className="p-pad text-sm text-muted">Reports are unavailable right now; they will be back shortly.</Card>}

      <section aria-label="Summary" className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {[
          { label: 'Income', value: data?.earned ?? 0, tone: '', sub: '' },
          { label: 'Spending', value: data?.spent ?? 0, tone: '', sub: data ? `${data.count} transaction${data.count === 1 ? '' : 's'}` : '' },
          { label: net >= 0 ? 'Saved' : 'Spent more than earned', value: Math.abs(net), tone: net >= 0 ? 'text-good-text' : 'text-critical',
            sub: net >= 0 && (data?.earned ?? 0) > 0 ? `${Math.round((net / data!.earned) * 100)}% of income` : '' },
          { label: 'Average a day', value: data && data.days > 0 ? Math.round(data.spent / data.days) : 0, tone: '', sub: data ? `over ${data.days} day${data.days === 1 ? '' : 's'}` : '' },
        ].map((x) => (
          <Card key={x.label} className="p-pad">
            <div className="text-xs text-muted">{x.label}</div>
            {isPending
              ? <Skeleton className="mt-1 h-8 w-32" />
              : <div className={cn('text-2xl font-semibold tracking-tight tabular', x.tone)} data-testid={`report-${x.label.toLowerCase().replace(/ /g, '-')}`}>{money(x.value)}</div>}
            <div className="text-xs text-subtle">{x.sub}</div>
          </Card>
        ))}
      </section>

      <div className="grid gap-6 lg:grid-cols-2">
        <ByCategory title="Spending by category" kind="EXPENSE" data={data} pending={isPending} categories={categories} period={period} money={money} txnSearch={txnSearch} />
        <ByCategory title="Income by category" kind="INCOME" data={data} pending={isPending} categories={categories} period={period} money={money} txnSearch={txnSearch} />
      </div>

      <MonthlyTrend period={period} locale={locale} data={data} />
      <DayByDay period={period} locale={locale} data={data} txnSearch={txnSearch} />

      {data && (
        <div className="grid gap-6 lg:grid-cols-2">
          <Breakdowns title="By account" empty="No spending from an account in this period."
            items={data.byAccount.filter((a) => a.spent > 0).map((a, i) => ({ key: a.accountId, label: accounts.find((x) => x.id === a.accountId)?.name ?? 'Account', value: a.spent, slot: (i % 8) + 1 }))}
            money={money} onSelect={(id) => void navigate({ to: '/transactions', search: { ...txnSearch, account: id } })} />
          <Breakdowns title="Top places" empty="No spending in this period."
            items={data.places.map((p, i) => ({ key: p.name, label: `${p.name} · ${p.count}×`, value: p.spent, slot: (i % 8) + 1 }))}
            money={money} onSelect={(name) => void navigate({ to: '/transactions', search: { ...txnSearch, q: name } })} />
          {data.byTag.length > 0 && (
            <Breakdowns title="By tag" empty=""
              items={data.byTag.map((t, i) => ({ key: t.tag, label: `#${t.tag} · ${t.count}`, value: t.spent, slot: (i % 8) + 1 }))}
              money={money} onSelect={(tag) => void navigate({ to: '/transactions', search: { ...txnSearch, tag } })} />
          )}
          <Biggest data={data} categories={categories} money={money} locale={locale} />
        </div>
      )}

      <BudgetAgainstActual period={period} locale={locale} money={money} />
    </div>
  )
}

/** Account, category, tag and words: the same filters as the transactions list. */
function ReportFilters({ search, set, categories, accounts }: {
  search: ReportsSearch; set: (s: Partial<ReportsSearch>) => void; categories: Category[]; accounts: Account[]
}) {
  const [text, setText] = useState(search.q ?? '')
  useEffect(() => setText(search.q ?? ''), [search.q])
  useEffect(() => {
    const next = text.trim() || undefined
    if (next === search.q) return
    const t = setTimeout(() => set({ q: next }), 300)
    return () => clearTimeout(t)
  }, [text]) // eslint-disable-line react-hooks/exhaustive-deps
  const parents = categories.filter((c) => !c.parentId)
  const any = search.account || search.category || search.tag || search.q
  return (
    <div className="flex flex-wrap items-center gap-2 print:hidden" role="group" aria-label="Report filters">
      <label className="relative min-w-0 flex-1 basis-48">
        <span className="sr-only">Search in the report</span>
        <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-subtle" />
        <Input type="search" value={text} onChange={(e) => setText(e.target.value)} placeholder="Words or an amount" className="pl-9" />
      </label>
      <SearchSelect aria-label="Account" value={search.account ?? ''} className="w-48" onChange={(v) => set({ account: v || undefined })}
        options={[{ value: '', label: 'All accounts' }, ...accountOptions(accounts)]} />
      <SearchSelect aria-label="Category" value={search.category ?? ''} className="w-52" onChange={(v) => set({ category: v || undefined })}
        options={[{ value: '', label: 'All categories' }, ...categoryOptions([...parents.filter((p) => p.kind === 'EXPENSE'), ...parents.filter((p) => p.kind === 'INCOME')]
          .flatMap((p) => [p, ...categories.filter((c) => c.parentId === p.id)]), categories, { group: true })]} />
      <Input aria-label="Tag" value={search.tag ?? ''} placeholder="Tag" maxLength={40} className="w-28 flex-none"
        onChange={(e) => set({ tag: e.target.value.replace(/^#/, '') || undefined })} />
      {any && (
        <Button variant="ghost" size="sm" onClick={() => { setText(''); set({ account: undefined, category: undefined, tag: undefined, q: undefined }) }}>
          <X />Clear filters
        </Button>
      )}
    </div>
  )
}

/** Each top-level category with its subcategories folded in; a row opens its transactions. */
function ByCategory({ title, kind, data, pending, categories, period, money, txnSearch }: {
  title: string; kind: 'EXPENSE' | 'INCOME'; data?: Breakdown; pending: boolean; categories: Category[]; period: Period
  money: (m: number) => string; txnSearch: Record<string, string | undefined>
}) {
  const rows = useMemo(() => {
    const byParent = new Map<string, number>()
    for (const t of (data?.byCategory ?? []).filter((x) => x.kind === kind && x.categoryId)) {
      const c = categories.find((x) => x.id === t.categoryId)
      const key = c?.parentId ?? t.categoryId!
      byParent.set(key, (byParent.get(key) ?? 0) + t.amount)
    }
    return [...byParent.entries()].map(([id, amount]) => ({ category: categories.find((c) => c.id === id), id, amount }))
      .filter((r) => r.amount !== 0).sort((a, b) => b.amount - a.amount)
  }, [data, categories, kind])
  const total = rows.reduce((s, r) => s + r.amount, 0)
  const max = Math.max(...rows.map((r) => r.amount), 1)

  return (
    <section aria-label={title} className="flex flex-col gap-2">
      <h2 className="text-sm font-medium text-muted">{title}</h2>
      <Card className="p-0">
        {pending ? <Skeleton className="m-pad h-40" /> : rows.length === 0 ? (
          <div className="flex flex-col items-center gap-2 px-pad py-10 text-center text-sm text-muted">
            <BarChart3 className="size-6" />{kind === 'EXPENSE' ? 'No spending in this period.' : 'No income in this period.'}
          </div>
        ) : (
          <ul className="divide-y divide-border">
            {rows.map((r) => (
              <li key={r.id}>
                <Link to="/transactions" search={{ ...txnSearch, category: r.id, from: period.from, to: period.to }}
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

/** Income against spending per calendar month, when the period spans more than one; a column zooms into that month. */
function MonthlyTrend({ period, locale, data }: { period: Period; locale: string; data?: Breakdown }) {
  const navigate = useNavigate({ from: '/reports' })
  const months = monthsIn(period.from, period.to)
  if (months.length <= 1 || !data) return null
  const at = (m: string) => data.byMonth.find((x) => x.month === m.slice(0, 7))
  return (
    <section aria-label="Month by month" className="flex flex-col gap-2">
      <h2 className="text-sm font-medium text-muted">Month by month</h2>
      <Card className="p-pad">
        <GroupedBars
          data={months.map((m) => {
            const [y, mo] = m.split('-').map(Number)
            return { label: new Date(y!, mo! - 1, 1).toLocaleDateString(locale, { month: 'short' }), values: [at(m)?.earned ?? 0, at(m)?.spent ?? 0] }
          })}
          series={[{ name: 'Income', slot: 1 }, { name: 'Spending', slot: 2 }]}
          format={(v) => formatMoneyWhole(v, undefined, locale)}
          formatAxis={(v) => formatMoneyShort(v, undefined, locale)}
          onSelect={(i) => {
            const [y, mo] = months[i]!.split('-').map(Number)
            const last = new Date(y!, mo!, 0).getDate()
            void navigate({ search: (s: ReportsSearch) => ({ ...s, period: 'custom', from: `${months[i]!.slice(0, 7)}-01`, to: `${months[i]!.slice(0, 7)}-${String(last).padStart(2, '0')}` }) })
          }}
        />
      </Card>
    </section>
  )
}

/**
 * When the money goes: for a month or less, a calendar shaded by each day's spending (a day opens its transactions);
 * for longer, which weekdays you spend most on.
 */
function DayByDay({ period, locale, data, txnSearch }: { period: Period; locale: string; data?: Breakdown; txnSearch: Record<string, string | undefined> }) {
  const navigate = useNavigate()
  const days = (Date.parse(`${period.to}T00:00`) - Date.parse(`${period.from}T00:00`)) / 86_400_000 + 1
  const [view, setView] = useState<'calendar' | 'weekdays'>('calendar')
  if (!data || data.spent === 0) return null
  const short = (m: number) => formatMoneyShort(m, undefined, locale)
  const calendar = days <= 42 && view === 'calendar'
  const on = (day: string) => data.byDay.find((d) => d.day === day)
  const weekdays = Array.from({ length: 7 }, (_, i) => new Date(2024, 0, 1 + i).toLocaleDateString(locale, { weekday: 'short' }))
  return (
    <section aria-label="Day by day" className="flex flex-col gap-2">
      <div className="flex items-center justify-between gap-2">
        <h2 className="text-sm font-medium text-muted">{calendar ? 'Day by day' : 'Which days you spend'}</h2>
        {days <= 42 && (
          <Segmented label="Days view" value={view} onChange={setView} className="print:hidden"
            options={[{ value: 'calendar', label: 'Calendar' }, { value: 'weekdays', label: 'Weekdays' }]} />
        )}
      </div>
      {calendar ? (
        <MonthCalendar start={period.from} end={period.to} locale={locale} label="Spending calendar" maxChips={0}
          flows={(day) => ({ in: on(day)?.earned ?? 0, out: on(day)?.spent ?? 0 })} formatFlow={short}
          chips={() => []}
          actions={(date) => [{ key: 'list', label: "See this day's transactions", onSelect: () => void navigate({ to: '/transactions', search: { ...txnSearch, from: date, to: date } }) }]} />
      ) : (
        <Card className="p-pad">
          <GroupedBars format={(v) => formatMoneyWhole(v, undefined, locale)} formatAxis={short}
            data={weekdays.map((label, i) => ({ label, values: [data.byWeekday.find((w) => w.weekday === i + 1)?.spent ?? 0] }))}
            series={[{ name: 'Spent', slot: 2 }]} />
        </Card>
      )}
    </section>
  )
}

function Breakdowns({ title, items, money, onSelect, empty }: {
  title: string; items: { key: string; label: string; value: number; slot: number }[]; money: (m: number) => string
  onSelect: (key: string) => void; empty: string
}) {
  return (
    <section aria-label={title} className="flex flex-col gap-2">
      <h2 className="text-sm font-medium text-muted">{title}</h2>
      <Card className="p-pad">
        {items.length === 0 ? <p className="text-sm text-muted">{empty}</p> : <BarList items={items.slice(0, 8)} format={money} onSelect={onSelect} />}
      </Card>
    </section>
  )
}

function Biggest({ data, categories, money, locale }: { data: Breakdown; categories: Category[]; money: (m: number) => string; locale: string }) {
  if (data.biggest.length === 0) return null
  return (
    <section aria-label="Biggest expenses" className="flex flex-col gap-2">
      <h2 className="text-sm font-medium text-muted">Biggest expenses</h2>
      <Card className="divide-y divide-border p-0">
        {data.biggest.slice(0, 6).map((b) => (
          <Link key={b.id} to="/transactions" search={{ open: b.id }} aria-label={`Open ${b.description}`}
            className="flex items-center gap-3 px-pad py-2 text-sm hover:bg-surface-2 first:rounded-t-card last:rounded-b-card">
            <CategoryIcon category={categories.find((c) => c.id === b.categoryId)} className="size-7" />
            <span className="min-w-0 flex-1 truncate">{b.description}</span>
            <span className="text-xs text-subtle">{new Date(`${b.occurredOn}T00:00`).toLocaleDateString(locale, { day: 'numeric', month: 'short' })}</span>
            <span className="tabular">{money(b.amount)}</span>
          </Link>
        ))}
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
            <Link key={r.month} to="/budgets" search={{ month: r.month }} aria-label={`Budgets for ${r.month}`}
              className="flex items-center gap-3 px-pad py-2.5 text-sm hover:bg-surface-2 first:rounded-t-card last:rounded-b-card" data-testid={`budget-row-${r.month}`}>
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
            </Link>
          )
        })}
      </Card>
    </section>
  )
}

/** The report's numbers as one CSV a spreadsheet opens: totals, categories, months, accounts, places and tags. */
function downloadSummary(data: Breakdown, categories: Category[], accounts: { id: string; name: string }[], period: Period, locale: string) {
  const rupees = (m: number) => (m / 100).toFixed(2)
  // A cell that starts with = + - @ would run as a formula in Excel; a quote mark keeps it text.
  const cell = (v: string | number) => {
    const s = String(v)
    const safe = /^[=+\-@\t\r]/.test(s) && typeof v === 'string' ? `'${s}` : s
    return /[",\n]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe
  }
  const name = (id: string | null) => {
    const c = categories.find((x) => x.id === id)
    const p = c?.parentId ? categories.find((x) => x.id === c.parentId) : undefined
    return c ? (p ? `${p.name} / ${c.name}` : c.name) : 'Uncategorised'
  }
  const rows: (string | number)[][] = [
    ['PayLedger report', `${period.from} to ${period.to}`],
    [],
    ['Total', 'Amount (INR)'],
    ['Income', rupees(data.earned)], ['Spending', rupees(data.spent)], ['Saved', rupees(data.earned - data.spent)],
    ['Average spending a day', rupees(data.days > 0 ? Math.round(data.spent / data.days) : 0)],
    [],
    ['Category', 'Type', 'Amount (INR)', 'Transactions'],
    ...data.byCategory.map((c) => [name(c.categoryId), c.kind === 'EXPENSE' ? 'Spending' : 'Income', rupees(c.amount), c.count]),
    [],
    ['Month', 'Income (INR)', 'Spending (INR)'],
    ...data.byMonth.map((m) => [m.month, rupees(m.earned), rupees(m.spent)]),
    [],
    ['Account', 'Spending (INR)', 'Income (INR)', 'Transactions'],
    ...data.byAccount.map((a) => [accounts.find((x) => x.id === a.accountId)?.name ?? 'Account', rupees(a.spent), rupees(a.earned), a.count]),
    [],
    ['Place', 'Spending (INR)', 'Times'],
    ...data.places.map((p) => [p.name, rupees(p.spent), p.count]),
    ...(data.byTag.length ? [[], ['Tag', 'Spending (INR)', 'Transactions'], ...data.byTag.map((t) => [t.tag, rupees(t.spent), t.count])] : []),
  ]
  const csv = '﻿' + rows.map((r) => r.map(cell).join(',')).join('\r\n') + '\r\n'
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
  const a = document.createElement('a')
  a.href = url
  a.download = `payledger-report-${period.from}-to-${period.to}.csv`
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
  void locale
}

function range(period: Period, locale: string): string {
  const fmt = (iso: string) => {
    const [y, m, d] = iso.split('-').map(Number)
    return new Date(y!, m! - 1, d!).toLocaleDateString(locale, { day: 'numeric', month: 'short', year: 'numeric' })
  }
  return `${fmt(period.from)} – ${fmt(period.to)}`
}
