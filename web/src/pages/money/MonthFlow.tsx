import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { Skeleton } from '@/components/ui/card'
import { isoDay, parse } from '@/components/MonthCalendar'
import { cn } from '@/lib/cn'
import { formatMoneyShort, formatMoneyWhole } from '@/lib/money'
import { useBreakdown, useDue, useSafeToSpend } from '@/state/money'
import { useProfile } from '@/state/profile'

/**
 * The budget month day by day, under safe to spend: money in (green) and money out (blue) so far, and where money out
 * is heading by the month's end once the bills and savings still due are paid (dashed). When that ends above the
 * money in, the gap is what you're short. Below it, the month's bills and savings: paid against still to pay.
 */
export function MonthFlow() {
  const { data: s } = useSafeToSpend()
  const locale = useProfile().data?.settings.locale ?? 'en-IN'
  const start = s?.start ?? ''
  const end = s?.end ?? ''
  const { data: flow, isPending } = useBreakdown({ from: start, to: end }, !!start)
  const { data: due = [] } = useDue(start, end)
  const [hover, setHover] = useState<number | null>(null)
  if (!s || !start) return null
  if (isPending || !flow) return <Skeleton className="h-56" />

  const money = (m: number) => formatMoneyWhole(m, s.currency, locale)
  const short = (m: number) => formatMoneyShort(m, s.currency, locale)
  const days: string[] = []
  for (let d = parse(start); isoDay(d) <= end; d = new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1)) days.push(isoDay(d))
  const today = isoDay(new Date())
  const todayIndex = Math.min(Math.max(days.findIndex((d) => d >= today), 0), days.length - 1)
  const at = (day: string) => flow.byDay.find((x) => x.day === day)

  // Unpaid bills and savings land on their due date; anything already overdue lands today.
  const unpaid = due.filter((x) => !x.paid && x.dueDate <= end)
  const dueOn = (i: number) => unpaid.filter((x) => (x.dueDate < today ? today : x.dueDate) === days[i]).reduce((sum, x) => sum + x.amount, 0)
  let cin = 0
  let cout = 0
  const rows = days.map((day, i) => {
    const past = i <= todayIndex
    if (past) {
      cin += at(day)?.earned ?? 0
      cout += at(day)?.spent ?? 0
    }
    // Ahead of today: what's due, and anything already entered for a later date.
    const planned = i >= todayIndex ? dueOn(i) + (i > todayIndex ? at(day)?.spent ?? 0 : 0) : 0
    return { day, in: cin, out: past ? cout : null as number | null, plannedAdd: planned }
  })
  // The projection starts from today's total and adds what's due.
  let projected = rows[todayIndex]!.out ?? 0
  const projection = rows.map((r, i) => {
    if (i < todayIndex) return null
    projected += r.plannedAdd
    return projected
  })
  let ahead = cin
  const inAhead = rows.map((r, i) => {
    if (i < todayIndex) return null
    if (i > todayIndex) ahead += at(r.day)?.earned ?? 0
    return ahead
  })
  const endIn = ahead
  const endOut = projection[projection.length - 1] ?? cout
  const gap = endOut - endIn

  const W = 600
  const H = 200
  const pad = { top: 12, right: 12, bottom: 22, left: 48 }
  const max = Math.max(endIn, endOut, cout, 1) * 1.08
  const x = (i: number) => pad.left + (i / Math.max(days.length - 1, 1)) * (W - pad.left - pad.right)
  const y = (v: number) => pad.top + (1 - v / max) * (H - pad.top - pad.bottom)
  const path = (vals: (number | null)[]) => vals.map((v, i) => (v === null ? null : `${x(i)},${y(v)}`)).filter(Boolean).join(' L')
  const inLine = rows.map((r, i) => (i <= todayIndex ? r.in : null))
  const outLine = rows.map((r) => r.out)
  const ticks = [0, max / 2, max].map((v) => Math.round(v))

  const paidDue = due.filter((x) => x.paid && x.dueDate >= start && x.dueDate <= end).reduce((sum, x) => sum + x.amount, 0)
  const leftDue = unpaid.reduce((sum, x) => sum + x.amount, 0)
  const h = hover ?? todayIndex
  const hRow = rows[h]!

  return (
    <section aria-label="This month, day by day" className="flex flex-col gap-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-sm font-medium text-muted">This month, day by day</h2>
        <p className="text-xs text-muted tabular" data-testid="flow-readout">
          {new Date(`${hRow.day}T00:00`).toLocaleDateString(locale, { day: 'numeric', month: 'short' })}:{' '}
          <span className="text-[var(--flow-in)]">in {money(h > todayIndex ? inAhead[h] ?? hRow.in : hRow.in)}</span> ·{' '}
          <span className="text-[var(--flow-out)]">out {money(hRow.out ?? projection[h] ?? 0)}{hRow.out === null && ' (planned)'}</span>
        </p>
      </div>
      <figure className="rounded-card border border-border bg-surface p-pad">
        <svg viewBox={`0 0 ${W} ${H}`} className="w-full" role="img"
          aria-label={`Money in ${money(endIn)}; money out ${money(cout)} so far, ${money(endOut)} by the month's end with what's still due${gap > 0 ? `; ${money(gap)} short` : ''}`}
          onMouseLeave={() => setHover(null)}
          onMouseMove={(e) => {
            const box = e.currentTarget.getBoundingClientRect()
            const fx = ((e.clientX - box.left) / box.width) * W
            const i = Math.round(((fx - pad.left) / (W - pad.left - pad.right)) * (days.length - 1))
            setHover(Math.min(Math.max(i, 0), days.length - 1))
          }}>
          {ticks.map((t) => (
            <g key={t}>
              <line x1={pad.left} x2={W - pad.right} y1={y(t)} y2={y(t)} stroke="var(--border)" strokeDasharray={t === 0 ? undefined : '2 4'} />
              <text x={pad.left - 6} y={y(t) + 4} textAnchor="end" className="fill-[var(--fg-subtle)] text-[10px] tabular">{short(t)}</text>
            </g>
          ))}
          {gap > 0 && (
            <polygon fill="var(--critical)" opacity="0.1"
              points={`${x(todayIndex)},${y(endIn)} ${x(days.length - 1)},${y(endIn)} ${x(days.length - 1)},${y(endOut)} ${x(todayIndex)},${y(projection[todayIndex] ?? 0)}`} />
          )}
          <line x1={x(todayIndex)} x2={x(todayIndex)} y1={pad.top} y2={H - pad.bottom} stroke="var(--border-strong)" strokeDasharray="3 3" />
          <text x={x(todayIndex)} y={H - 6} textAnchor="middle" className="fill-[var(--fg-muted)] text-[10px]">today</text>
          <path d={`M${path(inLine)}`} fill="none" stroke="var(--flow-in)" strokeWidth="2.5" strokeLinejoin="round" />
          <path d={`M${path(inAhead)}`} fill="none" stroke="var(--flow-in)" strokeWidth="1.5" strokeDasharray="4 4" />
          <path d={`M${path(outLine)}`} fill="none" stroke="var(--flow-out)" strokeWidth="2.5" strokeLinejoin="round" />
          <path d={`M${path(projection)}`} fill="none" stroke="var(--flow-out)" strokeWidth="2" strokeDasharray="5 4" />
          {hover !== null && <line x1={x(hover)} x2={x(hover)} y1={pad.top} y2={H - pad.bottom} stroke="var(--fg-subtle)" strokeWidth="0.75" />}
          <text x={pad.left} y={H - 6} className="fill-[var(--fg-subtle)] text-[10px]">{new Date(`${start}T00:00`).toLocaleDateString(locale, { day: 'numeric', month: 'short' })}</text>
          <text x={W - pad.right} y={H - 6} textAnchor="end" className="fill-[var(--fg-subtle)] text-[10px]">{new Date(`${end}T00:00`).toLocaleDateString(locale, { day: 'numeric', month: 'short' })}</text>
        </svg>
        <figcaption className="mt-3 flex flex-wrap items-center gap-x-5 gap-y-1 text-xs text-muted">
          <span className="flex items-center gap-1.5"><span className="h-0.5 w-4 rounded bg-[var(--flow-in)]" />Money in {money(endIn)}</span>
          <span className="flex items-center gap-1.5"><span className="h-0.5 w-4 rounded bg-[var(--flow-out)]" />Spent so far {money(cout)}</span>
          <span className="flex items-center gap-1.5"><span className="w-4 border-t-2 border-dashed border-[var(--flow-out)]" />By month end {money(endOut)}</span>
          <span className={cn('ml-auto font-medium', gap > 0 ? 'text-critical' : 'text-good-text')} data-testid="flow-gap">
            {gap > 0 ? `${money(gap)} short at this rate` : `${money(-gap)} to spare`}
          </span>
        </figcaption>
      </figure>

      {paidDue + leftDue > 0 && (
        <Link to="/plan" hash="due" className="flex flex-col gap-1.5 rounded-card border border-border bg-surface p-pad hover:border-border-strong" aria-label="Bills and savings this month">
          <span className="flex flex-wrap justify-between gap-2 text-sm">
            <span className="font-medium">Bills and savings this month</span>
            <span className="tabular text-muted" data-testid="bills-progress">
              <span className="text-[var(--flow-in)]">{money(paidDue)} paid</span> · <span className="text-fg">{money(leftDue)} still to pay</span>
            </span>
          </span>
          <span className="flex h-2.5 overflow-hidden rounded-full bg-surface-2" aria-hidden>
            <span className="bg-[var(--flow-in)]" style={{ width: `${(paidDue / (paidDue + leftDue)) * 100}%` }} />
            <span className="bg-[var(--flow-out)] opacity-40" style={{ width: `${(leftDue / (paidDue + leftDue)) * 100}%` }} />
          </span>
          <span className="text-xs text-subtle">{unpaid.length} still due{unpaid.some((u) => u.dueDate < today) && ', some overdue'}</span>
        </Link>
      )}
    </section>
  )
}
