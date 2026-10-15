import { CountUp } from '@/components/CountUp'
import { Progress } from '@/dashboard/charts'
import { Link } from '@tanstack/react-router'
import { cn } from '@/lib/cn'
import { formatMoneyWhole, moneyDefaults } from '@/lib/money'
import { useSafeToSpend } from '@/state/money'
import { useProfile } from '@/state/profile'

/**
 * Safe to spend this month: income, less what is spent, the bills still due and this month's savings.
 * Shared by the Plan page and the dashboard widget.
 */
export function SafeToSpendCard({ compact }: { compact?: boolean }) {
  const { data: s, isPending, isError } = useSafeToSpend()
  const locale = useProfile().data?.settings.locale ?? 'en-IN'
  const money = (minor: number) => formatMoneyWhole(minor, s?.currency ?? moneyDefaults().currency, locale)

  if (isPending) return <div className="h-28 animate-pulse rounded-control bg-surface-2" />
  if (isError || !s) return <p className="text-sm text-muted">Safe to spend is unavailable right now; it will be back shortly.</p>
  if (!s.known || s.safeToSpend === null) {
    return <p className="text-sm text-muted">Safe to spend is unavailable right now; it will be back shortly.</p>
  }
  if (s.income === 0) {
    return (
      <div className="flex flex-col items-start gap-1.5 text-sm text-muted">
        <p>Add this month's salary or other income to see what is safe to spend.</p>
        <Link to="/transactions" search={{ add: 'INCOME' }} className="text-fg underline underline-offset-4">Add income</Link>
      </div>
    )
  }

  const short = s.safeToSpend < 0
  // Each line opens where it comes from: this month's report, or the plan.
  const lines: [string, number, '/reports' | '/plan'][] = [
    ['Income', s.income ?? 0, '/reports'], ['Spent', -(s.spent ?? 0), '/reports'], ['Bills still due', -s.billsLeft, '/plan'], ['Savings', -s.savings, '/plan'],
  ]
  // Only once there is something to set aside, so people without big costs see four simple lines.
  if (s.setAside > 0) lines.push(['Set aside for big costs', -s.setAside, '/plan'])
  return (
    <div className="flex h-full flex-col justify-between gap-4">
      <div>
        {/* The dashboard widget already has the title. */}
        {!compact && <p className="text-sm text-muted">Safe to spend</p>}
        <div className={cn('font-semibold tracking-tight tabular', compact ? 'text-[2.25rem] leading-none' : 'text-4xl', short && 'text-critical')}
          data-testid="safe-to-spend">
          {short ? `${money(-s.safeToSpend)} short` : <CountUp to={s.safeToSpend} format={money} />}
        </div>
        <p className="mt-2 text-sm text-muted">
          {short
            ? 'Bills and savings due this month are more than what is left.'
            : <>about <span className="font-medium text-fg">{money(s.perDay ?? 0)}</span> a day for the next {s.daysLeft} day{s.daysLeft === 1 ? '' : 's'}</>}
        </p>
      </div>
      {compact && <MonthSoFar income={s.income ?? 0} spent={s.spent ?? 0} start={s.start} end={s.end} daysLeft={s.daysLeft} />}
      <nav aria-label="Where it comes from" className={cn('grid grid-cols-2 gap-x-4 gap-y-1 text-xs', lines.length > 4 ? 'sm:grid-cols-5' : 'sm:grid-cols-4')}>
        {lines.map(([label, value, to]) => (
          <Link key={label} to={to} search={to === '/reports' ? { period: 'this-month' } : {}} className="-m-1 flex flex-col rounded-control p-1 hover:bg-surface-2">
            <span className="text-subtle">{label}</span>
            <span className="tabular text-muted">{value < 0 ? `−${money(-value)}` : money(Math.abs(value))}</span>
          </Link>
        ))}
      </nav>
    </div>
  )
}

/** Spent against income so far, with a mark for how much of the month has gone: ahead of the mark is spending fast. */
function MonthSoFar({ income, spent, start, end, daysLeft }: { income: number; spent: number; start: string; end: string; daysLeft: number }) {
  const total = Math.max(1, Math.round((Date.parse(end) - Date.parse(start)) / 86_400_000) + 1)
  const gone = Math.min(1, Math.max(0, 1 - daysLeft / total))
  const used = income > 0 ? spent / income : 0
  const fast = used > gone + 0.1
  return (
    <div className="flex flex-col gap-1.5" data-testid="month-so-far">
      <div className="flex justify-between gap-2 text-xs text-muted">
        <span>Spent <span className={cn('font-medium', fast ? 'text-critical' : 'text-fg')}>{Math.round(used * 100)}%</span> of income</span>
        <span>{Math.round(gone * 100)}% of the month gone</span>
      </div>
      <div className="relative">
        <Progress value={used} color={fast ? 'var(--critical)' : undefined} label="Share of income spent" />
        <span className="absolute -top-1 h-4 w-0.5 rounded bg-fg" style={{ left: `${gone * 100}%` }} aria-hidden title="Today" />
      </div>
    </div>
  )
}
