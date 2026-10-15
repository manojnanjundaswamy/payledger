import { useGoals } from '@/state/goals'
import { useEffect, useState } from 'react'
import { Link, useNavigate } from '@tanstack/react-router'
import { useQueries } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { useGroups, type GroupBalances } from '@/state/groups'
import {
  ArrowDownLeft, ArrowLeftRight, ArrowUpRight, BarChart3, CalendarClock, Check, Goal, Home, ListChecks, ListOrdered,
  Inbox, Landmark, PieChart, Rocket, StickyNote, Upload, Users, Wallet, Zap,
} from 'lucide-react'
import { Badge } from '@/components/ui/card'
import { cn } from '@/lib/cn'
import type { SettingsTab } from '@/pages/settings/tabs'
import type { Widget } from '@/state/preferences'
import { usePreferences } from '@/state/PreferencesProvider'
import { BarList, BUDGET_STATUS, budgetStatus, GroupedBars, Progress } from './charts'
import { useWidgetEnv } from './context'
import { budgetPeriod, safeToSpend, sample } from './sample'
import {
  daysUntil, isoDate, useAccounts, useBudgets, useCategories, useCategoryTotals, useDue, useTransactions, type Budget, type Category,
} from '@/state/money'
import { SafeToSpendCard } from '@/pages/money/SafeToSpendCard'
import { CountUp } from '@/components/CountUp'
import { useCapture } from '@/state/capture'
import { useHousehold, useHouseholds } from '@/state/households'

export interface WidgetProps { widget: Widget; dashboardId: string; editing: boolean }

/** What a widget shows before the user has real data and sample data is off. */
function Empty({ children, phase = 'Phase 4' }: { children: React.ReactNode; phase?: string }) {
  return (
    <div className="flex h-full min-h-28 flex-col items-start justify-center gap-1.5 text-sm text-muted">
      {children}
      <span className="text-xs text-subtle">Arrives with {phase}.</span>
    </div>
  )
}

function SafeToSpend() {
  const { sample: on, real, money, profile } = useWidgetEnv()
  if (real) return <SafeToSpendCard compact />
  const period = budgetPeriod(profile.settings.budgetStartDay)
  if (!on) return <Empty>How much you can spend today, after bills, EMIs and savings.</Empty>
  const safe = safeToSpend()
  const perDay = Math.floor(safe / Math.max(1, period.daysLeft))
  const elapsed = 1 - period.daysLeft / period.daysTotal
  const used = sample.spent / sample.income
  return (
    <div className="flex h-full flex-col justify-between gap-4">
      <div>
        <div className="text-[2.25rem] leading-none font-semibold tracking-tight @md:text-[2.75rem]"><CountUp to={safe} format={(v) => money(v, { whole: true })} /></div>
        <div className="mt-2 text-sm text-muted">
          about <span className="font-medium text-fg">{money(perDay, { whole: true })}</span> a day for the next {period.daysLeft} days
        </div>
      </div>
      <div className="space-y-2">
        <div className="flex justify-between text-xs text-muted"><span>Spent {Math.round(used * 100)}% of income</span><span>{Math.round(elapsed * 100)}% of the month gone</span></div>
        <div className="relative">
          <Progress value={used} label="Share of income spent" />
          <span className="absolute -top-1 h-4 w-0.5 rounded bg-fg" style={{ left: `${elapsed * 100}%` }} aria-hidden title="Today" />
        </div>
        <div className="text-xs text-subtle">After {money(sample.commitmentsLeft, { whole: true })} of upcoming bills and {money(sample.savingsGoal, { whole: true })} to savings.</div>
      </div>
    </div>
  )
}

/** This month's budgets from core-service, the closest to their limit first. */
function RealBudgets({ widget }: { widget: Widget }) {
  const { money } = useWidgetEnv()
  const { data: month, isPending, isError } = useBudgets()
  const { data: categories = [] } = useCategories()
  if (isError) return <Unavailable what="Budgets" />
  if (isPending) return <div className="h-28 animate-pulse rounded-control bg-surface-2" />
  if (month.budgets.length === 0) {
    return (
      <div className="flex min-h-28 flex-col items-start justify-center gap-1.5 text-sm text-muted">
        No budgets yet.
        <Link to="/budgets" className="text-fg underline underline-offset-4">Set a budget</Link>
      </div>
    )
  }
  const ratio = (b: Budget) => (b.spent ?? 0) / b.limit
  const rows = [...month.budgets].sort((a, b) => ratio(b) - ratio(a)).slice(0, widget.size === 's' ? 3 : 8)
  const limit = month.budgets.reduce((t, b) => t + (b.available ?? b.limit), 0)
  const spent = month.budgets.reduce((t, b) => t + (b.spent ?? 0), 0)
  const over = month.budgets.filter((b) => b.status === 'OVER').length
  return (
    <div className="flex flex-col gap-3">
      {widget.size !== 's' && (
        <p className="text-sm text-muted">
          <span className="font-semibold text-fg"><CountUp to={spent} format={(v) => money(v, { whole: true })} /></span> of {money(limit, { whole: true })} budgeted
          {over > 0 && <span className="text-critical"> · {over} over</span>}
          {month.budgets.length > rows.length && <span className="text-subtle"> · {month.budgets.length - rows.length} more</span>}
        </p>
      )}
      <ul className="flex flex-col gap-3.5">
        {rows.map((b) => {
          const name = categories.find((c) => c.id === b.categoryId)?.name ?? 'Category'
          const look = b.status ? BUDGET_STATUS[b.status] : null
          return (
            <li key={b.categoryId}>
              <Link to="/transactions" search={{ category: b.categoryId, from: month.start, to: month.end }}
                aria-label={`${name}: spending this month`} className="group -m-1 block rounded-control p-1 hover:bg-surface-2">
              <div className="mb-1.5 flex items-center justify-between gap-2 text-[0.8125rem]">
                <span className="truncate font-medium group-hover:underline group-hover:underline-offset-4">{name}</span>
                <span className="flex shrink-0 items-center gap-2">
                  <span className="tabular text-muted">{b.spent === null ? '—' : money(b.spent, { whole: true })} <span className="text-subtle">/ {money(b.limit, { whole: true })}</span></span>
                  {look && <Badge tone={look.tone}><look.icon className="size-3" />{look.label}</Badge>}
                </span>
              </div>
              <Progress value={ratio(b)} color={look?.color} label={`${name} budget used`} />
              </Link>
            </li>
          )
        })}
      </ul>
      <Link to="/budgets" className="self-start text-xs text-muted underline underline-offset-4 hover:text-fg">All budgets</Link>
    </div>
  )
}

function Budgets({ widget }: WidgetProps) {
  const { sample: on, real, money } = useWidgetEnv()
  if (real) return <RealBudgets widget={widget} />
  if (!on) return <Empty>Monthly limits per category, with a heads-up before you go over.</Empty>
  const rows = widget.size === 's' ? sample.budgets.slice(0, 3) : sample.budgets
  return (
    <ul className="flex flex-col gap-3.5">
      {rows.map((b) => {
        const status = budgetStatus(b.spent, b.limit)
        return (
          <li key={b.name}>
            <div className="mb-1.5 flex items-center justify-between gap-2 text-[0.8125rem]">
              <span className="truncate font-medium">{b.name}</span>
              <span className="flex shrink-0 items-center gap-2">
                <span className="tabular text-muted">{money(b.spent, { whole: true })} <span className="text-subtle">/ {money(b.limit, { whole: true })}</span></span>
                <Badge tone={status.tone}><status.icon className="size-3" />{status.label}</Badge>
              </span>
            </div>
            <Progress value={status.ratio} color={status.color} label={`${b.name} budget used`} />
          </li>
        )
      })}
    </ul>
  )
}

/** "series-3" -> 3: categories carry the chart slot of their colour, so a colour always means the same category. */
function slotOf(category: Category | undefined): number {
  return Number(category?.color.replace('series-', '')) || 8
}

function SpendingByCategory({ widget }: WidgetProps) {
  const { sample: on, real, money, profile } = useWidgetEnv()
  if (real) return <RealSpendingByCategory widget={widget} budgetStartDay={profile.settings.budgetStartDay} />
  if (!on) return <Empty>Where your money went this month, by category.</Empty>
  const items = sample.categories.map((c) => ({ key: c.key, label: c.name, value: c.spent, slot: c.slot }))
  const total = items.reduce((s, i) => s + i.value, 0)
  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-baseline gap-2"><span className="text-2xl font-semibold tracking-tight">{money(total, { whole: true })}</span><span className="text-sm text-muted">spent this month</span></div>
      <BarList items={widget.size === 's' ? items.slice(0, 4) : items} format={(v) => money(v, { whole: true })} />
    </div>
  )
}

/** This budget month (it may start on payday), from the ledger. */
function RealSpendingByCategory({ widget, budgetStartDay }: { widget: Widget; budgetStartDay: number }) {
  const { money } = useWidgetEnv()
  const navigate = useNavigate()
  const period = budgetPeriod(budgetStartDay)
  const { data: totals, isPending, isError } = useCategoryTotals('EXPENSE', isoDate(period.start), isoDate(period.end))
  const { data: categories = [] } = useCategories()
  if (isError) return <Unavailable what="Spending" />
  if (isPending) return <div className="h-28 animate-pulse rounded-control bg-surface-2" />
  const items = (totals ?? [])
    .map((t) => {
      const c = categories.find((x) => x.id === t.categoryId)
      return { key: t.categoryId, label: c?.name ?? 'Category', value: t.amount, slot: slotOf(c) }
    })
    .sort((a, b) => b.value - a.value)
  if (items.length === 0) return <NothingYet>No spending yet this month.</NothingYet>
  const total = items.reduce((sum, i) => sum + i.value, 0)
  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-baseline gap-2"><span className="text-2xl font-semibold tracking-tight">{money(total, { whole: true })}</span><span className="text-sm text-muted">spent this month</span></div>
      <BarList items={widget.size === 's' ? items.slice(0, 4) : items} format={(v) => money(v, { whole: true })}
        onSelect={(categoryId) => void navigate({ to: '/transactions', search: { category: categoryId, from: isoDate(period.start), to: isoDate(period.end) } })} />
    </div>
  )
}

/** Last six calendar months, this one included. */
function RealCashFlow() {
  const { money, profile } = useWidgetEnv()
  const navigate = useNavigate()
  const now = new Date()
  const from = isoDate(new Date(now.getFullYear(), now.getMonth() - 5, 1))
  const to = isoDate(new Date(now.getFullYear(), now.getMonth() + 1, 0))
  const income = useCategoryTotals('INCOME', from, to, true)
  const spending = useCategoryTotals('EXPENSE', from, to, true)
  if (income.isError || spending.isError) return <Unavailable what="Cash flow" />
  if (income.isPending || spending.isPending) return <div className="h-40 animate-pulse rounded-control bg-surface-2" />
  const sum = (rows: { month: string | null; amount: number }[], month: string) =>
    rows.filter((r) => r.month === month).reduce((total, r) => total + r.amount, 0)
  const all = Array.from({ length: 6 }, (_, i) => new Date(now.getFullYear(), now.getMonth() - 5 + i, 1))
  // Empty months before the first entry say nothing: start where the data starts (always show three).
  const first = all.findIndex((m) => sum(income.data, isoDate(m)) > 0 || sum(spending.data, isoDate(m)) > 0)
  const months = all.slice(Math.min(first < 0 ? 3 : first, 3))
  return (
    <GroupedBars
      data={months.map((m) => ({
        label: m.toLocaleDateString(profile.settings.locale, { month: 'short' }),
        values: [sum(income.data, isoDate(m)), sum(spending.data, isoDate(m))],
      }))}
      series={[{ name: 'Income', slot: 1 }, { name: 'Spending', slot: 2 }]}
      format={(v) => money(v, { whole: true })}
      formatAxis={(v) => money(v, { compact: true })}
      onSelect={(i) => {
        const m = months[i]!
        void navigate({ to: '/reports', search: { period: 'custom', from: isoDate(m), to: isoDate(new Date(m.getFullYear(), m.getMonth() + 1, 0)) } })
      }}
    />
  )
}

function RealRecentTransactions({ widget }: { widget: Widget }) {
  const { money } = useWidgetEnv()
  const { data, isPending } = useTransactions({})
  const { data: categories = [] } = useCategories()
  const { data: accounts = [] } = useAccounts()
  if (isPending) return <div className="h-28 animate-pulse rounded-control bg-surface-2" />
  const rows = (data?.pages[0]?.items ?? []).slice(0, widget.size === 's' ? 4 : 6)
  if (rows.length === 0) return <NothingYet>Nothing recorded yet.</NothingYet>
  const accountName = (id: string | null) => accounts.find((a) => a.id === id)?.name ?? ''
  return (
    <div className="flex flex-col gap-1">
      <ul className="-mx-2 flex flex-col">
        {rows.map((t) => {
          const income = t.kind === 'INCOME'
          const via = t.kind === 'TRANSFER'
            ? `${accountName(t.accountId)} → ${accountName(t.toAccountId)}`
            : `${categories.find((c) => c.id === t.categoryId)?.name ?? ''} · ${accountName(t.accountId)}`
          return (
            <li key={t.id}>
              <Link to="/transactions" search={{ open: t.id }} aria-label={`Open ${t.description}`} className="flex items-center gap-3 rounded-control px-2 py-2 hover:bg-surface-2">
              <span className={cn('flex size-8 shrink-0 items-center justify-center rounded-full', income ? 'bg-good/10 text-good-text' : 'bg-surface-2 text-muted')}>
                {income ? <ArrowDownLeft className="size-4" /> : t.kind === 'TRANSFER' ? <ArrowLeftRight className="size-4" /> : <ArrowUpRight className="size-4" />}
              </span>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium">{t.description}</span>
                <span className="block truncate text-xs text-subtle">{via}</span>
              </span>
              <span className={cn('tabular shrink-0 text-sm font-medium', income && 'text-good-text')}>
                {money(t.kind === 'EXPENSE' ? -t.amount : t.amount, { sign: income })}
              </span>
              </Link>
            </li>
          )
        })}
      </ul>
      <Link to="/transactions" className="self-start text-xs text-muted underline underline-offset-4 hover:text-fg">See all</Link>
    </div>
  )
}

function Unavailable({ what }: { what: string }) {
  return <div className="flex min-h-28 items-center text-sm text-muted">{what} is unavailable right now; it will be back shortly.</div>
}

function NothingYet({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-28 flex-col items-start justify-center gap-1.5 text-sm text-muted">
      {children}
      <Link to="/transactions" search={{ add: 'EXPENSE' }} className="text-fg underline underline-offset-4">Add an expense</Link>
    </div>
  )
}

function CashFlow() {
  const { sample: on, real, money } = useWidgetEnv()
  if (real) return <RealCashFlow />
  if (!on) return <Empty>Income against spending over the last six months.</Empty>
  return (
    <GroupedBars
      data={sample.cashFlow.map((m) => ({ label: m.month, values: [m.income, m.spending] }))}
      series={[{ name: 'Income', slot: 1 }, { name: 'Spending', slot: 2 }]}
      format={(v) => money(v, { whole: true })}
      formatAxis={(v) => money(v, { compact: true })}
    />
  )
}

function RecentTransactions({ widget }: WidgetProps) {
  const { sample: on, real, money } = useWidgetEnv()
  if (real) return <RealRecentTransactions widget={widget} />
  if (!on) return <Empty>Your latest expenses and income, from every account.</Empty>
  const rows = widget.size === 's' ? sample.transactions.slice(0, 4) : sample.transactions
  return (
    <ul className="-mx-2 flex flex-col">
      {rows.map((t) => (
        <li key={t.id} className="flex items-center gap-3 rounded-control px-2 py-2 hover:bg-surface-2">
          <span className={cn('flex size-8 shrink-0 items-center justify-center rounded-full', t.amount > 0 ? 'bg-good/10 text-good-text' : 'bg-surface-2 text-muted')}>
            {t.amount > 0 ? <ArrowDownLeft className="size-4" /> : <ArrowUpRight className="size-4" />}
          </span>
          <span className="min-w-0 flex-1">
            <span className="block truncate text-sm font-medium">{t.name}</span>
            <span className="block truncate text-xs text-subtle">{t.category} · {t.via} · {relativeDay(t.daysAgo)}</span>
          </span>
          <span className={cn('tabular shrink-0 text-sm font-medium', t.amount > 0 && 'text-good-text')}>{money(t.amount, { sign: t.amount > 0 })}</span>
        </li>
      ))}
    </ul>
  )
}

/** Unpaid bills and savings due in the next 30 days (and any overdue from the last 30). */
function RealUpcomingBills({ widget }: { widget: Widget }) {
  const { money, profile } = useWidgetEnv()
  const now = new Date()
  const { data: due, isPending, isError } = useDue(
    isoDate(new Date(now.getFullYear(), now.getMonth(), now.getDate() - 30)),
    isoDate(new Date(now.getFullYear(), now.getMonth(), now.getDate() + 30)))
  if (isError) return <Unavailable what="Upcoming bills" />
  if (isPending) return <div className="h-28 animate-pulse rounded-control bg-surface-2" />
  const unpaid = due.filter((d) => !d.paid)
  if (unpaid.length === 0) {
    return (
      <div className="flex min-h-28 flex-col items-start justify-center gap-1.5 text-sm text-muted">
        Nothing due in the next 30 days.
        <Link to="/plan" className="text-fg underline underline-offset-4">Add a bill or SIP</Link>
      </div>
    )
  }
  const rows = unpaid.slice(0, widget.size === 's' ? 3 : 5)
  const soon = unpaid.filter((d) => daysUntil(d.dueDate) <= 10).reduce((sum, d) => sum + d.amount, 0)
  return (
    <div className="flex flex-col gap-3">
      <div className="text-sm text-muted"><span className="font-medium text-fg">{money(soon, { whole: true })}</span> due in the next 10 days</div>
      <ul className="flex flex-col gap-2">
        {rows.map((d) => {
          const days = daysUntil(d.dueDate)
          const [, m, day] = d.dueDate.split('-').map(Number)
          return (
            <li key={`${d.commitmentId}/${d.dueDate}`}>
              <Link to="/plan" search={{ open: d.commitmentId }} aria-label={`Open ${d.name}`} className="-mx-1 flex items-center gap-3 rounded-control px-1 py-0.5 hover:bg-surface-2">
              <span className="flex w-11 shrink-0 flex-col items-center rounded-control border border-border py-1 leading-none">
                <span className="text-[0.625rem] text-subtle uppercase">{new Date(2000, m! - 1, 1).toLocaleDateString(profile.settings.locale, { month: 'short' })}</span>
                <span className="text-[0.9375rem] font-semibold">{day}</span>
              </span>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium">{d.name}</span>
                <span className={cn('text-xs', days < 0 ? 'text-critical' : 'text-subtle')}>
                  {d.kind === 'BILL' ? 'Bill' : 'Saving'} · {days === 0 ? 'today' : days > 0 ? `in ${days} day${days === 1 ? '' : 's'}` : `overdue by ${-days} day${days === -1 ? '' : 's'}`}
                </span>
              </span>
              <span className="tabular shrink-0 text-sm">{money(d.amount, { whole: true })}</span>
              </Link>
            </li>
          )
        })}
      </ul>
      <Link to="/plan" className="self-start text-xs text-muted underline underline-offset-4 hover:text-fg">Your plan</Link>
    </div>
  )
}

function UpcomingBills({ widget }: WidgetProps) {
  const { sample: on, real, money } = useWidgetEnv()
  if (real) return <RealUpcomingBills widget={widget} />
  if (!on) return <Empty>EMIs, card dues, SIPs and subscriptions, before they hit.</Empty>
  const rows = widget.size === 's' ? sample.bills.slice(0, 3) : sample.bills
  const total = sample.bills.filter((b) => b.inDays <= 10).reduce((s, b) => s + b.amount, 0)
  return (
    <div className="flex flex-col gap-3">
      <div className="text-sm text-muted"><span className="font-medium text-fg">{money(total, { whole: true })}</span> due in the next 10 days</div>
      <ul className="flex flex-col gap-2">
        {rows.map((b) => (
          <li key={b.id} className="flex items-center gap-3">
            <span className="flex w-11 shrink-0 flex-col items-center rounded-control border border-border py-1 leading-none">
              <span className="text-[0.625rem] text-subtle uppercase">{inDaysMonth(b.inDays)}</span>
              <span className="text-[0.9375rem] font-semibold">{inDaysDate(b.inDays)}</span>
            </span>
            <span className="min-w-0 flex-1">
              <span className="block truncate text-sm font-medium">{b.name}</span>
              <span className="text-xs text-subtle">{b.kind} · in {b.inDays} days</span>
            </span>
            <span className="tabular shrink-0 text-sm">{money(b.amount, { whole: true })}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

/** Your groups and where you stand in each, from the ledger; each row opens the group. */
function RealGroups() {
  const { money } = useWidgetEnv()
  const { data: groups, isPending, isError } = useGroups()
  const open = (groups ?? []).filter((g) => !g.archived)
  const balances = useQueries({
    queries: open.map((g) => ({ queryKey: ['groups', g.id, 'balances'], queryFn: () => api<GroupBalances>(`/api/core/groups/${g.id}/balances`) })),
  })
  if (isError) return <Unavailable what="Groups" />
  if (isPending) return <div className="h-28 animate-pulse rounded-control bg-surface-2" />
  if (open.length === 0) {
    return (
      <div className="flex min-h-28 flex-col items-start justify-center gap-1.5 text-sm text-muted">
        Split trips, flats and dinners with friends.
        <Link to="/groups" className="text-fg underline underline-offset-4">Start a group</Link>
      </div>
    )
  }
  // A member's balance is positive when they owe the group.
  const rows = open.map((g, i) => {
    const b = balances[i]?.data
    return { g, value: b?.known && b.balance ? b.balance[g.you] ?? 0 : null }
  })
  const owed = rows.reduce((s, r) => s + Math.max(0, -(r.value ?? 0)), 0)
  const owe = rows.reduce((s, r) => s + Math.max(0, r.value ?? 0), 0)
  return (
    <div className="flex flex-col gap-3">
      <div className="grid grid-cols-2 gap-2">
        <div className="rounded-control bg-surface-2 p-3"><div className="text-xs text-muted">You are owed</div><div className="mt-0.5 text-lg font-semibold text-good-text">{money(owed, { whole: true })}</div></div>
        <div className="rounded-control bg-surface-2 p-3"><div className="text-xs text-muted">You owe</div><div className="mt-0.5 text-lg font-semibold">{money(owe, { whole: true })}</div></div>
      </div>
      <ul className="-mx-1 flex flex-col">
        {rows.slice(0, 5).map(({ g, value }) => (
          <li key={g.id}>
            <Link to="/groups/$groupId" params={{ groupId: g.id }} className="flex items-center gap-3 rounded-control px-1 py-1.5 hover:bg-surface-2">
              <span className="flex size-8 items-center justify-center rounded-control bg-surface-2 text-base" aria-hidden>{g.emoji ?? '👥'}</span>
              <span className="min-w-0 flex-1"><span className="block truncate text-sm font-medium">{g.name}</span><span className="text-xs text-subtle">{g.members} people</span></span>
              <span className={cn('text-sm font-medium tabular', value !== null && value < 0 && 'text-good-text', (value === 0 || value === null) && 'text-subtle')}>
                {value === null ? '—' : value === 0 ? 'Settled' : value < 0 ? `+${money(-value, { whole: true })}` : `−${money(value, { whole: true })}`}
              </span>
            </Link>
          </li>
        ))}
      </ul>
      <Link to="/groups" className="self-start text-xs text-muted underline underline-offset-4 hover:text-fg">All groups</Link>
    </div>
  )
}

function Groups() {
  const { sample: on, real, money } = useWidgetEnv()
  if (real) return <RealGroups />
  if (!on) return <Empty phase="Phase 5">Trips, flats and friends: who owes whom, settled in fewest payments.</Empty>
  const owed = sample.groups.filter((g) => g.balance > 0).reduce((s, g) => s + g.balance, 0)
  const owe = -sample.groups.filter((g) => g.balance < 0).reduce((s, g) => s + g.balance, 0)
  return (
    <div className="flex flex-col gap-3">
      <div className="grid grid-cols-2 gap-2">
        <div className="rounded-control bg-surface-2 p-3"><div className="text-xs text-muted">You are owed</div><div className="mt-0.5 text-lg font-semibold text-good-text">{money(owed, { whole: true })}</div></div>
        <div className="rounded-control bg-surface-2 p-3"><div className="text-xs text-muted">You owe</div><div className="mt-0.5 text-lg font-semibold">{money(owe, { whole: true })}</div></div>
      </div>
      <ul className="flex flex-col gap-1">
        {sample.groups.map((g) => (
          <li key={g.id} className="flex items-center gap-3 py-1.5">
            <span className="flex size-8 items-center justify-center rounded-control bg-surface-2 text-base" aria-hidden>{g.emoji}</span>
            <span className="min-w-0 flex-1"><span className="block truncate text-sm font-medium">{g.name}</span><span className="text-xs text-subtle">{g.members} people</span></span>
            <span className={cn('text-sm font-medium tabular', g.balance > 0 && 'text-good-text', g.balance === 0 && 'text-subtle')}>
              {g.balance === 0 ? 'Settled' : g.balance > 0 ? `+${money(g.balance, { whole: true })}` : money(g.balance, { whole: true })}
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}

function Goals({ widget }: WidgetProps) {
  const { sample: on, real, money } = useWidgetEnv()
  if (real) return <RealGoals widget={widget} />
  if (!on) return <Empty>Savings goals with a target and a date.</Empty>
  const rows = widget.size === 's' ? sample.goals.slice(0, 2) : sample.goals
  return (
    <ul className="flex flex-col gap-4">
      {rows.map((g) => (
        <li key={g.id}>
          <div className="mb-1.5 flex items-baseline justify-between gap-2 text-[0.8125rem]">
            <span className="truncate font-medium">{g.name}</span>
            <span className="tabular shrink-0 text-muted">{Math.round((g.saved / g.target) * 100)}%</span>
          </div>
          <Progress value={g.saved / g.target} label={`${g.name} progress`} />
          <div className="mt-1 text-xs text-subtle">{money(g.saved, { compact: true })} of {money(g.target, { compact: true })} · by {g.by}</div>
        </li>
      ))}
    </ul>
  )
}

/** Your first household: this month's shared spending and who recorded it. */
function Household() {
  const { money } = useWidgetEnv()
  const { data: list, isPending } = useHouseholds()
  const first = list?.[0]
  const { data: h } = useHousehold(first?.id)
  if (isPending) return <div className="h-28 animate-pulse rounded-control bg-surface-2" />
  if (!first) {
    return (
      <div className="flex min-h-28 flex-col items-start justify-center gap-1.5 text-sm text-muted">
        One budget for the whole family, with everyone's own money kept private.
        <Link to="/household" className="text-fg underline underline-offset-4">Start a household</Link>
      </div>
    )
  }
  if (!h) return <div className="h-28 animate-pulse rounded-control bg-surface-2" />
  return (
    <Link to="/household/$householdId" params={{ householdId: h.id }} className="-m-1 flex flex-col gap-4 rounded-control p-1 hover:bg-surface-2/50 @lg:flex-row @lg:items-center @lg:gap-8">
      <div className="@lg:w-56">
        <div className="text-sm text-muted">{h.name}</div>
        <div className="mt-1 text-2xl font-semibold tracking-tight"><CountUp to={h.spent} format={(v) => money(v, { whole: true })} /></div>
        <div className="text-xs text-subtle">spent together this month</div>
      </div>
      <ul className="flex flex-1 flex-col gap-2">
        {h.contributions.map((c, i) => (
          <li key={c.userId} className="grid grid-cols-[6rem_1fr_auto] items-center gap-2 text-xs">
            <span className="truncate">{c.name}</span>
            <span className="h-1.5 overflow-hidden rounded-full bg-surface-2">
              <span className="block h-full origin-left animate-[grow-x_700ms_cubic-bezier(.2,.8,.2,1)] rounded-full" style={{ width: `${c.share * 100}%`, background: `var(--series-${(i % 8) + 1})` }} />
            </span>
            <span className="tabular text-muted">{money(c.spent, { whole: true })}</span>
          </li>
        ))}
      </ul>
    </Link>
  )
}

function GettingStarted() {
  const { prefs, profile, real } = useWidgetEnv()
  const steps: { done: boolean; label: string; tab: SettingsTab | null; to?: '/welcome' }[] = [
    { done: true, label: 'Create your account', tab: null },
    { done: prefs.theme.accent !== null || prefs.theme.palette !== 'classic' || prefs.theme.mode !== 'system', label: 'Make it yours: pick a theme', tab: 'appearance' },
    { done: profile.settings.budgetStartDay !== 1, label: 'Set the day your month starts (payday)', tab: 'regional' },
    { done: prefs.dashboards.length > 1 || prefs.dashboards.some((d) => d.widgets.some((w) => w.title || w.type === 'note')), label: 'Customize a dashboard', tab: 'dashboards' },
    { done: real, label: 'Set up your accounts and bills (2 minutes)', tab: null, to: '/welcome' },
  ]
  const done = steps.filter((s) => s.done).length
  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-3">
        <div className="flex-1"><Progress value={done / steps.length} label="Setup progress" /></div>
        <span className="text-xs text-muted tabular">{done}/{steps.length}</span>
      </div>
      <ul className="flex flex-col gap-1">
        {steps.map((s) => (
          <li key={s.label}>
            {s.tab && !s.done ? (
              <Link to="/settings" search={{ tab: s.tab }} className="flex items-center gap-2.5 rounded-control px-1 py-1.5 text-sm hover:bg-surface-2"><StepDot done={s.done} />{s.label}</Link>
            ) : s.to && !s.done ? (
              <Link to={s.to} className="flex items-center gap-2.5 rounded-control px-1 py-1.5 text-sm hover:bg-surface-2"><StepDot done={s.done} />{s.label}</Link>
            ) : (
              <span className={cn('flex items-center gap-2.5 px-1 py-1.5 text-sm', s.done && 'text-muted line-through decoration-border-strong')}><StepDot done={s.done} />{s.label}</span>
            )}
          </li>
        ))}
      </ul>
    </div>
  )
}

function StepDot({ done }: { done: boolean }) {
  return (
    <span className={cn('flex size-5 shrink-0 items-center justify-center rounded-full border', done ? 'border-transparent bg-accent text-accent-fg' : 'border-border-strong')}>
      {done && <Check className="size-3" strokeWidth={3} />}
    </span>
  )
}

function QuickActions() {
  const navigate = useNavigate()
  const add = (kind: 'EXPENSE' | 'INCOME' | 'TRANSFER') => () => void navigate({ to: '/transactions', search: { add: kind } })
  const actions = [
    { icon: ArrowUpRight, label: 'Expense', go: add('EXPENSE') }, { icon: ArrowDownLeft, label: 'Income', go: add('INCOME') },
    { icon: ArrowLeftRight, label: 'Transfer', go: add('TRANSFER') }, { icon: Users, label: 'Split', go: () => void navigate({ to: '/groups' }) },
    { icon: Upload, label: 'Import', go: () => void navigate({ to: '/import' }) }, { icon: Zap, label: 'Pay bill', go: () => void navigate({ to: '/plan' }) },
  ]
  return (
    <div className="grid grid-cols-3 gap-2 @md:grid-cols-6">
      {actions.map((a) => (
        <button key={a.label} onClick={a.go}
          className="flex flex-col items-center gap-1.5 rounded-control border border-border py-3 text-xs font-medium text-muted transition-all hover:-translate-y-0.5 hover:border-border-strong hover:text-fg hover:shadow-card">
          <a.icon className="size-5" />{a.label}
        </button>
      ))}
    </div>
  )
}

/** A sticky note. Real data: saved with the dashboard like everything else. */
function Note({ widget, dashboardId }: WidgetProps) {
  const { dispatch } = usePreferences()
  const [text, setText] = useState(widget.text ?? '')
  useEffect(() => setText(widget.text ?? ''), [widget.text])
  useEffect(() => {
    if (text === (widget.text ?? '')) return
    const t = setTimeout(() => dispatch({ type: 'updateWidget', dashboard: dashboardId, id: widget.id, patch: { text } }), 400)
    return () => clearTimeout(t)
  }, [text, widget.text, widget.id, dashboardId, dispatch])
  return (
    <textarea
      value={text}
      maxLength={1000}
      onChange={(e) => setText(e.target.value)}
      placeholder="Write anything: a reminder, this month's plan, who owes you lunch…"
      aria-label="Note"
      className="h-full min-h-28 w-full resize-none bg-transparent text-sm leading-relaxed outline-none placeholder:text-subtle"
    />
  )
}

export const WIDGETS = {
  gettingStarted: { name: 'Getting started', description: 'Your setup checklist.', icon: Rocket, defaultSize: 'm', sample: false, component: GettingStarted },
  safeToSpend: { name: 'Safe to spend', description: 'What you can spend today after bills and savings.', icon: Wallet, defaultSize: 'm', sample: true, component: SafeToSpend },
  budgets: { name: 'Budgets', description: 'Monthly limits per category, with status.', icon: ListChecks, defaultSize: 'm', sample: true, component: Budgets },
  spendingByCategory: { name: 'Spending by category', description: 'Where the money went this month.', icon: PieChart, defaultSize: 'm', sample: true, component: SpendingByCategory },
  cashFlow: { name: 'Cash flow', description: 'Income against spending, last 6 months.', icon: BarChart3, defaultSize: 'l', sample: true, component: CashFlow },
  recentTransactions: { name: 'Recent transactions', description: 'Latest activity across accounts.', icon: ListOrdered, defaultSize: 'm', sample: true, component: RecentTransactions },
  upcomingBills: { name: 'Upcoming bills', description: 'EMIs, card dues, SIPs, subscriptions.', icon: CalendarClock, defaultSize: 's', sample: true, component: UpcomingBills },
  groups: { name: 'Groups & balances', description: 'Who owes whom in your groups.', icon: Users, defaultSize: 'm', sample: true, component: Groups },
  goals: { name: 'Savings goals', description: 'Progress towards what you are saving for.', icon: Goal, defaultSize: 'm', sample: true, component: Goals },
  household: { name: 'Household', description: 'The family budget and who paid what.', icon: Home, defaultSize: 'l', sample: false, component: Household },
  toReview: { name: 'To review', description: 'Bank alerts waiting to be added (Auto-capture).', icon: Inbox, defaultSize: 's', sample: false, component: ToReview },
  netWorth: { name: 'Net worth', description: 'What you have minus what you owe, by kind of account.', icon: Landmark, defaultSize: 'm', sample: false, component: NetWorth },
  quickActions: { name: 'Quick actions', description: 'One tap to record or split.', icon: Zap, defaultSize: 'm', sample: false, component: QuickActions },
  note: { name: 'Note', description: 'A sticky note for reminders and plans.', icon: StickyNote, defaultSize: 's', sample: false, component: Note },
} as const satisfies Record<Widget['type'], {
  name: string; description: string; icon: typeof Wallet; defaultSize: Widget['size']; sample: boolean; component: (p: WidgetProps) => React.ReactNode
}>

/** Auto-capture's waiting entries: the count, the first few, one tap to review. */
function ToReview() {
  const { money } = useWidgetEnv()
  const { data, isPending } = useCapture()
  if (isPending) return <div className="h-24 animate-pulse rounded-control bg-surface-2" />
  const pending = (data?.items ?? []).filter((i) => i.status === 'PENDING')
  if (!data || pending.length === 0) {
    return (
      <div className="flex min-h-24 flex-col items-start justify-center gap-1.5 text-sm text-muted">
        {data?.lastEmailAt ? 'All caught up: nothing waiting.' : 'Forward bank emails or paste SMS, and entries wait here to be checked.'}
        <Link to="/capture" className="text-fg underline underline-offset-4">{data?.lastEmailAt ? 'Paste SMS' : 'Set up auto-capture'}</Link>
      </div>
    )
  }
  return (
    <div className="flex flex-col gap-3">
      <p className="text-sm text-muted"><span className="text-2xl font-semibold text-fg"><CountUp to={pending.length} format={String} /></span> to review</p>
      <ul className="flex flex-col gap-1">
        {pending.slice(0, 3).map((i) => (
          <li key={i.id} className="flex items-center justify-between gap-3 text-sm">
            <span className="min-w-0 truncate">{i.merchant ?? i.bank ?? 'Bank alert'}</span>
            <span className={cn('shrink-0 tabular', i.kind === 'INCOME' ? 'text-[var(--flow-in)]' : 'text-[var(--flow-out)]')}>
              {i.kind === 'INCOME' ? '+' : '−'}{money(i.amount ?? 0, { whole: true })}
            </span>
          </li>
        ))}
      </ul>
      <Link to="/capture" className="self-start rounded-control bg-accent px-3 py-1.5 text-xs font-medium text-accent-fg hover:opacity-90">Review {pending.length > 1 ? 'them' : 'it'}</Link>
    </div>
  )
}

/** What you have minus what you owe, across your accounts, by kind. */
function NetWorth() {
  const { money } = useWidgetEnv()
  const { data: accounts, isPending } = useAccounts()
  if (isPending) return <div className="h-24 animate-pulse rounded-control bg-surface-2" />
  const live = (accounts ?? []).filter((a) => !a.archived && a.currency === 'INR' && a.balance !== null)
  if (live.length === 0) {
    return <div className="flex min-h-24 flex-col items-start justify-center gap-1.5 text-sm text-muted">Add your accounts to see your net worth.<Link to="/accounts" className="text-fg underline underline-offset-4">Accounts</Link></div>
  }
  const by = (kinds: string[]) => live.filter((a) => kinds.includes(a.kind)).reduce((t, a) => t + (a.balance ?? 0), 0)
  // A card's balance is what you owe on it; "Friends & groups" below zero is what you owe friends.
  const parts = [
    { label: 'Bank', value: by(['BANK']), slot: 1 }, { label: 'Cash & wallets', value: by(['CASH', 'WALLET']), slot: 3 },
    { label: 'Friends owe you', value: by(['OWED']), slot: 4 }, { label: 'Cards owed', value: -by(['CARD']), slot: 2 },
  ].filter((p) => p.value !== 0)
  const net = parts.reduce((t, p) => t + p.value, 0)
  const scale = Math.max(1, ...parts.map((p) => Math.abs(p.value)))
  return (
    <Link to="/accounts" className="-m-1 flex flex-col gap-3 rounded-control p-1 hover:bg-surface-2/50">
      <span className={cn('text-[2rem] leading-none font-semibold tracking-tight', net < 0 && 'text-critical')}>
        <CountUp to={net} format={(v) => money(v, { whole: true })} />
      </span>
      <ul className="flex flex-col gap-2">
        {parts.map((p) => (
          <li key={p.label} className="grid grid-cols-[7rem_1fr_auto] items-center gap-2 text-xs">
            <span className="text-muted">{p.label}</span>
            <span className="h-1.5 overflow-hidden rounded-full bg-surface-2">
              <span className="block h-full origin-left animate-[grow-x_700ms_cubic-bezier(.2,.8,.2,1)] rounded-full"
                style={{ width: `${(Math.abs(p.value) / scale) * 100}%`, background: p.value < 0 ? 'var(--critical)' : `var(--series-${p.slot})` }} />
            </span>
            <span className={cn('tabular', p.value < 0 ? 'text-critical' : 'text-fg')}>{p.value < 0 ? '−' : ''}{money(Math.abs(p.value), { compact: true })}</span>
          </li>
        ))}
      </ul>
    </Link>
  )
}

function relativeDay(daysAgo: number) {
  return daysAgo === 0 ? 'Today' : daysAgo === 1 ? 'Yesterday' : `${daysAgo} days ago`
}
function inDaysDateObj(days: number) { const d = new Date(); d.setDate(d.getDate() + days); return d }
function inDaysMonth(days: number) { return inDaysDateObj(days).toLocaleDateString('en-IN', { month: 'short' }) }
function inDaysDate(days: number) { return inDaysDateObj(days).getDate() }

/** Your goals, nearest date first: how far along, and what each needs this month. Each opens the Goals page. */
function RealGoals({ widget }: Pick<WidgetProps, 'widget'>) {
  const { data: goals, isPending } = useGoals()
  const { money } = useWidgetEnv()
  if (isPending) return <div className="h-28 animate-pulse rounded-control bg-surface-2" />
  const rows = (goals ?? []).slice(0, widget.size === 's' ? 2 : 4)
  if (rows.length === 0) return <NothingYet>No goals yet. <Link to="/goals" className="underline">Add one</Link>: a trip, a phone, an emergency fund.</NothingYet>
  return (
    <ul className="flex flex-col gap-4">
      {rows.map((g) => {
        const pct = g.saved === null ? 0 : Math.min(1, g.saved / g.target)
        return (
          <li key={g.id}>
            <Link to="/goals" className="-m-1 block rounded-control p-1 hover:bg-surface-2" aria-label={`${g.name}: ${Math.round(pct * 100)}%`}>
              <div className="mb-1.5 flex items-baseline justify-between gap-2 text-[0.8125rem]">
                <span className="truncate font-medium">{g.emoji ?? '🎯'} {g.name}</span>
                <span className="tabular shrink-0 text-muted">{Math.round(pct * 100)}%</span>
              </div>
              <Progress value={pct} color={g.status === 'BEHIND' ? 'var(--warning)' : 'var(--flow-in)'} label={`${g.name} progress`} />
              <div className="mt-1 text-xs text-subtle">
                {g.saved === null ? '—' : money(g.saved, { compact: true })} of {money(g.target, { compact: true })}
                {g.perMonth !== null && g.status !== 'REACHED' && ` · ${money(g.perMonth, { compact: true })} a month`}
                {g.status === 'BEHIND' && ' · behind'}
              </div>
            </Link>
          </li>
        )
      })}
    </ul>
  )
}
