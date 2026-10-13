import { useState } from 'react'
import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { ChevronLeft, ChevronRight, PieChart, Plus, TrendingUp } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge, Card, Skeleton } from '@/components/ui/card'
import { cn } from '@/lib/cn'
import { formatMoneyWhole } from '@/lib/money'
import { BUDGET_STATUS, Progress } from '@/dashboard/charts'
import { shiftMonth, useBudgets, useCategories, type Budget, type BudgetMonth } from '@/state/money'
import { useProfile } from '@/state/profile'
import { BudgetDialog, monthLabel } from './BudgetDialog'
import { CategoryIcon } from './parts'

/** This month's budgets (or another month's, with the switcher): spent against the limit, status and pace. */
export function BudgetsPage() {
  const { month: wanted } = useSearch({ from: '/app/budgets' })
  const navigate = useNavigate({ from: '/budgets' })
  const { data: month, isPending, isError } = useBudgets(wanted)
  const locale = useProfile().data?.settings.locale ?? 'en-IN'
  const [editing, setEditing] = useState<Budget | 'new' | null>(null)
  const go = (by: number) => month && void navigate({ search: { month: shiftMonth(month.month, by) } })

  if (isError) return <Card className="mx-auto max-w-3xl p-pad text-sm text-critical">Couldn't load your budgets. Refresh to try again.</Card>
  if (isPending || !month) return <div className="mx-auto max-w-3xl"><Skeleton className="h-64" /></div>

  const current = month.daysElapsed > 0 && month.daysElapsed < month.days
  const money = (minor: number) => formatMoneyWhole(minor, 'INR', locale)

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div className="flex items-center gap-1">
          <Button variant="ghost" size="icon" onClick={() => go(-1)} aria-label="Previous month"><ChevronLeft /></Button>
          <div className="min-w-40 text-center">
            <h2 className="font-semibold" data-testid="budget-month">{monthLabel(month.month, locale)}</h2>
            <p className="text-xs text-subtle">{range(month, locale)}</p>
          </div>
          <Button variant="ghost" size="icon" onClick={() => go(1)} aria-label="Next month"><ChevronRight /></Button>
          {wanted && (
            <Button variant="ghost" size="sm" onClick={() => void navigate({ search: {} })}>This month</Button>
          )}
        </div>
        <Button variant="primary" onClick={() => setEditing('new')}><Plus />Set budget</Button>
      </div>

      {month.budgets.length === 0 ? (
        <Card className="flex flex-col items-center gap-3 px-pad py-12 text-center">
          <PieChart className="size-8 text-muted" />
          <p className="font-medium">No budgets for {monthLabel(month.month, locale)}</p>
          <p className="max-w-sm text-sm text-muted">
            Pick a category, like Groceries or Eating out, and a monthly limit. You'll see how it's going, and a warning before you go over.
          </p>
          <Button variant="primary" onClick={() => setEditing('new')}><Plus />Set budget</Button>
        </Card>
      ) : (
        <>
          <Summary month={month} current={current} money={money} />
          {!month.spentKnown && (
            <Card className="p-pad text-sm text-muted">Spending is unavailable right now; it will be back shortly. Your limits are below.</Card>
          )}
          <section aria-label="Budgets" className="flex flex-col gap-3">
            {month.budgets.map((b) => <BudgetCard key={b.categoryId} budget={b} month={month} money={money} onEdit={() => setEditing(b)} />)}
          </section>
        </>
      )}

      <p className="text-xs text-subtle">
        Your month starts on day {Number(month.start.slice(8))}. <Link to="/settings" search={{ tab: 'regional' }} className="underline underline-offset-4 hover:text-fg">Change it</Link> to match your payday.
      </p>

      <BudgetDialog open={editing !== null} onOpenChange={(o) => !o && setEditing(null)} month={month}
        budget={editing === 'new' || editing === null ? undefined : editing} />
    </div>
  )
}

function Summary({ month, current, money }: { month: BudgetMonth; current: boolean; money: (m: number) => string }) {
  const limit = month.budgets.reduce((sum, b) => sum + b.limit, 0)
  const spent = month.budgets.reduce((sum, b) => sum + (b.spent ?? 0), 0)
  const left = month.days - month.daysElapsed + 1
  return (
    <Card className="flex flex-col gap-3 p-pad">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <div>
          <span className="text-2xl font-semibold tracking-tight tabular" data-testid="budget-total">
            {month.spentKnown ? money(spent) : '—'}
          </span>
          <span className="text-sm text-muted"> of {money(limit)} budgeted</span>
        </div>
        {current && <span className="text-sm text-muted">{left === 1 ? 'Last day' : `${left} days left`}</span>}
      </div>
      {month.spentKnown && (
        <div className="relative">
          <Progress value={limit > 0 ? spent / limit : 0} color={BUDGET_STATUS[spent > limit ? 'OVER' : spent * 5 >= limit * 4 ? 'CLOSE' : 'ON_TRACK'].color} label="All budgets used" />
          {current && (
            <span className="absolute -top-1 h-4 w-0.5 rounded bg-fg" style={{ left: `${(month.daysElapsed / month.days) * 100}%` }} aria-hidden title="Today" />
          )}
        </div>
      )}
    </Card>
  )
}

function BudgetCard({ budget: b, month, money, onEdit }: { budget: Budget; month: BudgetMonth; money: (m: number) => string; onEdit: () => void }) {
  const { data: categories = [] } = useCategories()
  const category = categories.find((c) => c.id === b.categoryId)
  const name = category?.name ?? 'Category'
  const look = b.status ? BUDGET_STATUS[b.status] : null
  const pace = b.projected !== null && b.projected > b.limit
  return (
    <button type="button" onClick={onEdit} aria-label={`Edit ${name} budget`}
      className="flex w-full flex-col gap-2.5 rounded-card border border-border bg-surface p-pad text-left shadow-sm hover:bg-surface-2">
      <div className="flex items-center gap-3">
        <CategoryIcon category={category} />
        <span className="min-w-0 flex-1">
          <span className="block truncate font-medium">{name}</span>
          <span className="block text-xs text-subtle tabular">
            {b.spent === null ? `${money(b.limit)} a month` : `${money(b.spent)} of ${money(b.limit)}`}
          </span>
        </span>
        {look && <Badge tone={look.tone}><look.icon className="size-3" />{look.label}</Badge>}
      </div>
      {b.spent !== null && look && <Progress value={b.spent / b.limit} color={look.color} label={`${name} budget used`} />}
      {b.remaining !== null && (
        <p className={cn('text-sm', b.remaining < 0 ? 'text-critical' : 'text-muted')}>
          {b.remaining < 0 ? `${money(-b.remaining)} over` : `${money(b.remaining)} left`}
        </p>
      )}
      {b.projected !== null && (
        <p className={cn('flex items-center gap-1.5 text-sm', pace ? 'text-fg' : 'text-muted')}>
          <TrendingUp className={cn('size-4 shrink-0', pace && 'text-warning')} />
          {pace
            ? `At this rate you'll spend ${money(b.projected)} by ${shortDate(month.end)}, ${money(b.projected - b.limit)} over.`
            : `At this rate, about ${money(b.projected)} by ${shortDate(month.end)}.`}
        </p>
      )}
    </button>
  )
}

function shortDate(iso: string, locale = 'en-IN'): string {
  const [y, m, d] = iso.split('-').map(Number)
  return new Date(y!, m! - 1, d!).toLocaleDateString(locale, { day: 'numeric', month: 'short' })
}

function range(month: BudgetMonth, locale: string): string {
  return `${shortDate(month.start, locale)} – ${shortDate(month.end, locale)}`
}
