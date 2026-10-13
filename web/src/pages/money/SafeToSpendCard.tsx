import { Link } from '@tanstack/react-router'
import { cn } from '@/lib/cn'
import { formatMoneyWhole } from '@/lib/money'
import { useSafeToSpend } from '@/state/money'
import { useProfile } from '@/state/profile'

/**
 * Safe to spend this month: income, less what is spent, the bills still due and this month's savings.
 * Shared by the Plan page and the dashboard widget.
 */
export function SafeToSpendCard({ compact }: { compact?: boolean }) {
  const { data: s, isPending, isError } = useSafeToSpend()
  const locale = useProfile().data?.settings.locale ?? 'en-IN'
  const money = (minor: number) => formatMoneyWhole(minor, s?.currency ?? 'INR', locale)

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
  const lines: [string, number][] = [['Income', s.income ?? 0], ['Spent', -(s.spent ?? 0)], ['Bills still due', -s.billsLeft], ['Savings', -s.savings]]
  return (
    <div className="flex h-full flex-col justify-between gap-4">
      <div>
        {/* The dashboard widget already has the title. */}
        {!compact && <p className="text-sm text-muted">Safe to spend</p>}
        <div className={cn('font-semibold tracking-tight tabular', compact ? 'text-[2.25rem] leading-none' : 'text-4xl', short && 'text-critical')}
          data-testid="safe-to-spend">
          {short ? `${money(-s.safeToSpend)} short` : money(s.safeToSpend)}
        </div>
        <p className="mt-2 text-sm text-muted">
          {short
            ? 'Bills and savings due this month are more than what is left.'
            : <>about <span className="font-medium text-fg">{money(s.perDay ?? 0)}</span> a day for the next {s.daysLeft} day{s.daysLeft === 1 ? '' : 's'}</>}
        </p>
      </div>
      <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-xs sm:grid-cols-4">
        {lines.map(([label, value]) => (
          <div key={label} className="flex flex-col">
            <dt className="text-subtle">{label}</dt>
            <dd className="tabular text-muted">{value < 0 ? `−${money(-value)}` : money(Math.abs(value))}</dd>
          </div>
        ))}
      </dl>
    </div>
  )
}
