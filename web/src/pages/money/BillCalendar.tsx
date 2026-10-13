import { Link } from '@tanstack/react-router'
import { CalendarPlus, Pencil, Receipt } from 'lucide-react'
import { DropdownMenu } from 'radix-ui'
import { cn } from '@/lib/cn'
import { daysUntil, type Due } from '@/state/money'

/**
 * The budget month as a calendar (from the market scan: Monarch's bill calendar): every day from the
 * period's start to its end, weeks from Monday, with what is due that day. Paid items are ticked, overdue
 * ones are red; marking paid stays in the list view.
 */
export function BillCalendar({ start, end, due, money, locale, onOpen, onAdd }: {
  start: string; end: string; due: Due[]; money: (m: number) => string; locale: string
  /** Opens a bill or saving when its chip is clicked. */
  onOpen?: (commitmentId: string) => void
  /** Adds a bill or saving first due on that day. */
  onAdd?: (date: string) => void
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
            <DropdownMenu.Root key={key}>
            <DropdownMenu.Trigger asChild>
            <div role="button" tabIndex={0} className={cn('flex min-h-20 cursor-pointer flex-col gap-1 border-b border-l border-border p-1.5 text-left outline-none hover:bg-surface-2 focus-visible:ring-2 focus-visible:ring-[var(--accent)] data-[state=open]:bg-surface-2', key === today && 'bg-accent-soft')}
              data-testid={`day-${key}`} aria-label={day.toLocaleDateString(locale, { day: 'numeric', month: 'long' })}>
              <span className={cn('text-xs tabular', key === today ? 'font-semibold text-fg' : 'text-muted')}>
                {day.getDate() === 1 || key === start ? day.toLocaleDateString(locale, { day: 'numeric', month: 'short' }) : day.getDate()}
              </span>
              {items.map((x) => {
                const overdue = !x.paid && daysUntil(x.dueDate) < 0
                return (
                  <button type="button" key={x.commitmentId} title={`${x.name}: ${money(x.amount)}${x.paid ? ', paid' : overdue ? ', overdue' : ''}`}
                    // A chip opens its bill at once; the rest of the day opens the menu.
                    onPointerDown={(e) => e.stopPropagation()} onKeyDown={(e) => e.stopPropagation()}
                    onClick={(e) => { e.stopPropagation(); onOpen?.(x.commitmentId) }} aria-label={`Open ${x.name}`}
                    className={cn('truncate rounded px-1 py-0.5 text-left text-[0.6875rem] leading-tight hover:ring-1 hover:ring-border-strong',
                      x.paid ? 'bg-good/10 text-good-text line-through decoration-1' : overdue ? 'bg-critical/10 text-critical' : 'bg-surface-2 text-fg')}>
                    {x.paid && '✓ '}{x.name} · {money(x.amount)}
                  </button>
                )
              })}
            </div>
            </DropdownMenu.Trigger>
            <DayMenu date={key} label={day.toLocaleDateString(locale, { weekday: 'long', day: 'numeric', month: 'long' })}
              items={items} money={money} onOpen={onOpen} onAdd={onAdd} past={key <= today} />
            </DropdownMenu.Root>
          )
        })}
      </div>
    </div>
  )
}

const item = 'flex cursor-pointer select-none items-center gap-2 rounded-[calc(var(--radius-sm)-3px)] px-2.5 py-2 text-sm outline-none data-[highlighted]:bg-surface-2 [&_svg]:size-4'

/** What a day offers: change what's due on it, or add something new on that date. */
function DayMenu({ date, label, items, money, onOpen, onAdd, past }: {
  date: string; label: string; items: Due[]; money: (m: number) => string
  onOpen?: (id: string) => void; onAdd?: (date: string) => void; past: boolean
}) {
  return (
    <DropdownMenu.Portal>
      <DropdownMenu.Content align="start" sideOffset={4} aria-label={label}
        className="z-50 min-w-60 rounded-control border border-border bg-surface p-1 shadow-lift data-[state=open]:animate-[pop-in_120ms_ease-out]">
        <DropdownMenu.Label className="px-2.5 pt-2 pb-1 text-[0.6875rem] font-medium tracking-wide text-subtle uppercase">{label}</DropdownMenu.Label>
        {items.map((x) => (
          <DropdownMenu.Item key={x.commitmentId} className={item} onSelect={() => onOpen?.(x.commitmentId)}>
            <Pencil />Edit {x.name}<span className="ml-auto pl-3 text-xs text-subtle tabular">{money(x.amount)}</span>
          </DropdownMenu.Item>
        ))}
        {items.length > 0 && <DropdownMenu.Separator className="my-1 h-px bg-border" />}
        <DropdownMenu.Item className={item} onSelect={() => onAdd?.(date)}>
          <CalendarPlus />Add a bill or saving due this day
        </DropdownMenu.Item>
        {past && (
          <DropdownMenu.Item className={item} asChild>
            <Link to="/transactions" search={{ add: 'EXPENSE', date }}><Receipt />Record an expense on this day</Link>
          </DropdownMenu.Item>
        )}
      </DropdownMenu.Content>
    </DropdownMenu.Portal>
  )
}

function parse(isoDate: string): Date {
  const [y, m, d] = isoDate.split('-').map(Number)
  return new Date(y!, m! - 1, d!)
}

function iso(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
