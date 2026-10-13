import { useEffect, useState } from 'react'
import { Link } from '@tanstack/react-router'
import {
  ArrowDownLeft, ArrowLeftRight, ArrowUpRight, BarChart3, CalendarClock, Check, Goal, Home, ListChecks, ListOrdered,
  PieChart, Rocket, StickyNote, Upload, Users, Wallet, Zap,
} from 'lucide-react'
import { toast } from 'sonner'
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
  const rows = [...month.budgets].sort((a, b) => ratio(b) - ratio(a)).slice(0, widget.size === 's' ? 3 : 5)
  return (
    <div className="flex flex-col gap-3">
      <ul className="flex flex-col gap-3.5">
        {rows.map((b) => {
          const name = categories.find((c) => c.id === b.categoryId)?.name ?? 'Category'
          const look = b.status ? BUDGET_STATUS[b.status] : null
          return (
            <li key={b.categoryId}>
              <div className="mb-1.5 flex items-center justify-between gap-2 text-[0.8125rem]">
                <span className="truncate font-medium">{name}</span>
                <span className="flex shrink-0 items-center gap-2">
                  <span className="tabular text-muted">{b.spent === null ? '—' : money(b.spent, { whole: true })} <span className="text-subtle">/ {money(b.limit, { whole: true })}</span></span>
                  {look && <Badge tone={look.tone}><look.icon className="size-3" />{look.label}</Badge>}
                </span>
              </div>
              <Progress value={ratio(b)} color={look?.color} label={`${name} budget used`} />
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
      <BarList items={widget.size === 's' ? items.slice(0, 4) : items} format={(v) => money(v, { whole: true })} />
    </div>
  )
}

/** Last six calendar months, this one included. */
function RealCashFlow() {
  const { money, profile } = useWidgetEnv()
  const now = new Date()
  const from = isoDate(new Date(now.getFullYear(), now.getMonth() - 5, 1))
  const to = isoDate(new Date(now.getFullYear(), now.getMonth() + 1, 0))
  const income = useCategoryTotals('INCOME', from, to, true)
  const spending = useCategoryTotals('EXPENSE', from, to, true)
  if (income.isError || spending.isError) return <Unavailable what="Cash flow" />
  if (income.isPending || spending.isPending) return <div className="h-40 animate-pulse rounded-control bg-surface-2" />
  const sum = (rows: { month: string | null; amount: number }[], month: string) =>
    rows.filter((r) => r.month === month).reduce((total, r) => total + r.amount, 0)
  const months = Array.from({ length: 6 }, (_, i) => new Date(now.getFullYear(), now.getMonth() - 5 + i, 1))
  return (
    <GroupedBars
      data={months.map((m) => ({
        label: m.toLocaleDateString(profile.settings.locale, { month: 'short' }),
        values: [sum(income.data, isoDate(m)), sum(spending.data, isoDate(m))],
      }))}
      series={[{ name: 'Income', slot: 1 }, { name: 'Spending', slot: 2 }]}
      format={(v) => money(v, { whole: true })}
      formatAxis={(v) => money(v, { compact: true })}
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
            <li key={t.id} className="flex items-center gap-3 rounded-control px-2 py-2 hover:bg-surface-2">
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
            <li key={`${d.commitmentId}/${d.dueDate}`} className="flex items-center gap-3">
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

function Groups() {
  const { sample: on, money } = useWidgetEnv()
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
  const { sample: on, money } = useWidgetEnv()
  if (!on) return <Empty phase="Phase 6">Savings goals with a target and a date.</Empty>
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

function Household() {
  const { sample: on, money } = useWidgetEnv()
  if (!on) return <Empty phase="Phase 5">One budget for the whole family, with private money kept private.</Empty>
  const h = sample.household
  const status = budgetStatus(h.spent, h.budget)
  return (
    <div className="flex flex-col gap-4 @lg:flex-row @lg:items-center @lg:gap-8">
      <div className="@lg:w-64">
        <div className="text-sm text-muted">{h.name}</div>
        <div className="mt-1 flex items-baseline gap-2"><span className="text-2xl font-semibold tracking-tight">{money(h.spent, { whole: true })}</span><span className="text-sm text-muted">of {money(h.budget, { whole: true })}</span></div>
        <div className="mt-2"><Progress value={status.ratio} color={status.color} label="Household budget used" /></div>
      </div>
      <ul className="flex flex-1 flex-col gap-2">
        {h.members.map((m) => (
          <li key={m.name} className="flex items-center gap-3">
            <span className="flex size-8 items-center justify-center rounded-full bg-surface-3 text-xs font-semibold">{m.name[0]}</span>
            <span className="min-w-0 flex-1 text-sm"><span className="font-medium">{m.name}</span>{m.dependent && <span className="ml-2 text-xs text-subtle">dependent</span>}</span>
            <span className="tabular text-sm text-muted">{m.dependent ? '–' : `paid ${money(m.paid, { whole: true })}`}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

function GettingStarted() {
  const { prefs, profile, real } = useWidgetEnv()
  const steps: { done: boolean; label: string; tab: SettingsTab | null }[] = [
    { done: true, label: 'Create your account', tab: null },
    { done: prefs.theme.accent !== null || prefs.theme.mode !== 'system', label: 'Make it yours: pick a theme', tab: 'appearance' },
    { done: profile.settings.budgetStartDay !== 1, label: 'Set the day your month starts (payday)', tab: 'regional' },
    { done: prefs.dashboards.length > 1 || prefs.dashboards.some((d) => d.widgets.some((w) => w.title || w.type === 'note')), label: 'Customize a dashboard', tab: null },
    { done: real, label: 'Add your first account', tab: null },
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
  const soon = (what: string) => toast(`${what} is coming in Phase 4`)
  const actions = [
    { icon: ArrowUpRight, label: 'Expense' }, { icon: ArrowDownLeft, label: 'Income' },
    { icon: ArrowLeftRight, label: 'Transfer' }, { icon: Users, label: 'Split' }, { icon: Upload, label: 'Import' }, { icon: Zap, label: 'Pay bill' },
  ]
  return (
    <div className="grid grid-cols-3 gap-2 @md:grid-cols-6">
      {actions.map((a) => (
        <button key={a.label} onClick={() => soon(a.label)}
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
  household: { name: 'Household', description: 'The family budget and who paid what.', icon: Home, defaultSize: 'l', sample: true, component: Household },
  quickActions: { name: 'Quick actions', description: 'One tap to record or split.', icon: Zap, defaultSize: 'm', sample: false, component: QuickActions },
  note: { name: 'Note', description: 'A sticky note for reminders and plans.', icon: StickyNote, defaultSize: 's', sample: false, component: Note },
} as const satisfies Record<Widget['type'], {
  name: string; description: string; icon: typeof Wallet; defaultSize: Widget['size']; sample: boolean; component: (p: WidgetProps) => React.ReactNode
}>

function CountUp({ to, format }: { to: number; format: (v: number) => string }) {
  const [value, setValue] = useState(to)
  useEffect(() => {
    if (matchMedia('(prefers-reduced-motion: reduce)').matches || document.documentElement.dataset.motion === 'reduced') {
      setValue(to)
      return
    }
    const start = performance.now()
    let frame = 0
    const tick = (now: number) => {
      const t = Math.min(1, (now - start) / 700)
      setValue(Math.round(to * (1 - (1 - t) ** 3)))
      if (t < 1) frame = requestAnimationFrame(tick)
    }
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [to])
  return <span className="tabular">{format(value)}</span>
}

function relativeDay(daysAgo: number) {
  return daysAgo === 0 ? 'Today' : daysAgo === 1 ? 'Yesterday' : `${daysAgo} days ago`
}
function inDaysDateObj(days: number) { const d = new Date(); d.setDate(d.getDate() + days); return d }
function inDaysMonth(days: number) { return inDaysDateObj(days).toLocaleDateString('en-IN', { month: 'short' }) }
function inDaysDate(days: number) { return inDaysDateObj(days).getDate() }
