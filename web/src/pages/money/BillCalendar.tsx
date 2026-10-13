import { cn } from '@/lib/cn'
import { daysUntil, type Due } from '@/state/money'

/**
 * The budget month as a calendar (from the market scan: Monarch's bill calendar): every day from the
 * period's start to its end, weeks from Monday, with what is due that day. Paid items are ticked, overdue
 * ones are red; marking paid stays in the list view.
 */
export function BillCalendar({ start, end, due, money, locale }: {
  start: string; end: string; due: Due[]; money: (m: number) => string; locale: string
}) {
  const first = parse(start)
  const last = parse(end)
  const days: Date[] = []
  for (let d = new Date(first); d <= last; d = new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1)) days.push(d)
  // Monday first, as in India's calendars.
  const lead = (first.getDay() + 6) % 7
  const weekdays = Array.from({ length: 7 }, (_, i) => new Date(2024, 0, 1 + i).toLocaleDateString(locale, { weekday: 'short' }))
  const today = iso(new Date())

  return (
    <div className="overflow-x-auto rounded-card border border-border bg-surface">
      <div className="grid min-w-[42rem] grid-cols-7">
        {weekdays.map((w) => <div key={w} className="border-b border-border px-2 py-1.5 text-xs font-medium text-muted">{w}</div>)}
        {Array.from({ length: lead }, (_, i) => <div key={`lead-${i}`} className="border-b border-border bg-surface-2/40" />)}
        {days.map((day) => {
          const key = iso(day)
          const items = due.filter((x) => x.dueDate === key)
          return (
            <div key={key} className={cn('flex min-h-20 flex-col gap-1 border-b border-l border-border p-1.5', key === today && 'bg-accent-soft')}
              data-testid={`day-${key}`} aria-label={day.toLocaleDateString(locale, { day: 'numeric', month: 'long' })}>
              <span className={cn('text-xs tabular', key === today ? 'font-semibold text-fg' : 'text-muted')}>
                {day.getDate() === 1 || key === start ? day.toLocaleDateString(locale, { day: 'numeric', month: 'short' }) : day.getDate()}
              </span>
              {items.map((x) => {
                const overdue = !x.paid && daysUntil(x.dueDate) < 0
                return (
                  <span key={x.commitmentId} title={`${x.name}: ${money(x.amount)}${x.paid ? ', paid' : overdue ? ', overdue' : ''}`}
                    className={cn('truncate rounded px-1 py-0.5 text-[0.6875rem] leading-tight',
                      x.paid ? 'bg-good/10 text-good-text line-through decoration-1' : overdue ? 'bg-critical/10 text-critical' : 'bg-surface-2 text-fg')}>
                    {x.paid && '✓ '}{x.name} · {money(x.amount)}
                  </span>
                )
              })}
            </div>
          )
        })}
      </div>
    </div>
  )
}

function parse(isoDate: string): Date {
  const [y, m, d] = isoDate.split('-').map(Number)
  return new Date(y!, m! - 1, d!)
}

function iso(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
