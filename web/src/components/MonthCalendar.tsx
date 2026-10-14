import type { ReactNode } from 'react'
import { DropdownMenu } from 'radix-ui'
import { cn } from '@/lib/cn'

/** Something shown on a day: a chip that opens it directly. */
export interface CalendarChip {
  id: string
  label: string
  title?: string
  tone?: 'default' | 'good' | 'critical' | 'done'
  onOpen?: () => void
  /** How it reads in the day's menu, like "Edit Rent ₹25,000". */
  menuLabel?: ReactNode
}

/** An action in a day's menu. */
export interface DayAction {
  key: string
  label: ReactNode
  icon?: ReactNode
  onSelect?: () => void
  /** Render as a link (or anything clickable) instead of a button. */
  asChild?: ReactNode
}

/**
 * A month (or any run of days) as a calendar, Monday first, as in India's calendars: each day shows its chips and an
 * optional note (a total, say). Clicking a chip opens it; clicking the rest of the day opens a menu of what can be done
 * on that date, like a Confluence calendar. The Plan page's bills, transactions and group expenses all use it.
 */
export function MonthCalendar({ start, end, locale, chips, note, actions, heat, flows, formatFlow, maxChips = 3, label }: {
  start: string
  end: string
  locale: string
  chips: (day: string) => CalendarChip[]
  /** A short line under the date, such as the day's spending. */
  note?: (day: string) => ReactNode
  /** What the day's menu offers; the chips are listed first as "Edit …" items. */
  actions: (day: string, past: boolean) => DayAction[]
  /** 0–1: shades the day, for a spending heat map. */
  heat?: (day: string) => number
  /**
   * Money in and out on a day: shown as +in (green) and −out (blue) under the date, and the day is tinted by its
   * net, green when more came in, blue when more went out. A legend explains the colours.
   */
  flows?: (day: string) => { in: number; out: number }
  formatFlow?: (minor: number) => string
  maxChips?: number
  label?: string
}) {
  const first = parse(start)
  const last = parse(end)
  const days: Date[] = []
  for (let d = new Date(first); d <= last; d = new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1)) days.push(d)
  const lead = (first.getDay() + 6) % 7
  const weekdays = Array.from({ length: 7 }, (_, i) => new Date(2024, 0, 1 + i)).map((d) => ({
    short: d.toLocaleDateString(locale, { weekday: 'short' }), narrow: d.toLocaleDateString(locale, { weekday: 'narrow' }),
  }))
  const today = isoDay(new Date())
  const maxFlow = flows ? Math.max(1, ...days.map((d) => { const f = flows(isoDay(d)); return Math.abs(f.in - f.out) })) : 1

  return (
    <div className="flex flex-col gap-2">
    {/* Fits any width: on a phone (a narrow container) a day shows its date, the net and a dot per item; tapping it lists them. */}
    <div className="@container rounded-card border border-border bg-surface" role="grid" aria-label={label}>
      <div className="grid grid-cols-7">
        {weekdays.map((w) => (
          <div key={w.short} className="border-b border-border px-1 py-1.5 text-center text-xs font-medium text-muted @2xl:px-2 @2xl:text-left">
            <span className="@md:hidden">{w.narrow}</span><span className="hidden @md:inline">{w.short}</span>
          </div>
        ))}
        {Array.from({ length: lead }, (_, i) => <div key={`lead-${i}`} className="border-b border-border bg-surface-2/40" />)}
        {days.map((day) => {
          const key = isoDay(day)
          const items = chips(key)
          const shown = items.slice(0, maxChips)
          const h = heat?.(key) ?? 0
          const f = flows?.(key)
          const net = f ? f.in - f.out : 0
          const dayName = day.toLocaleDateString(locale, { weekday: 'long', day: 'numeric', month: 'long' })
          return (
            <DropdownMenu.Root key={key}>
              <DropdownMenu.Trigger asChild>
                <div role="button" tabIndex={0} data-testid={`day-${key}`} aria-label={day.toLocaleDateString(locale, { day: 'numeric', month: 'long' })}
                  className={cn('relative flex min-h-14 min-w-0 cursor-pointer flex-col gap-0.5 border-b border-l border-border p-1 text-left @2xl:min-h-20 @2xl:gap-1 @2xl:p-1.5 outline-none hover:bg-surface-2 focus-visible:ring-2 focus-visible:ring-[var(--accent)] data-[state=open]:bg-surface-2',
                    key === today && 'bg-accent-soft')}>
                  {h > 0 && <span aria-hidden className="pointer-events-none absolute inset-0 bg-critical" style={{ opacity: 0.04 + h * 0.16 }} />}
                  {f && net !== 0 && (
                    <span aria-hidden className="pointer-events-none absolute inset-0"
                      style={{ background: net > 0 ? 'var(--flow-in)' : 'var(--flow-out)', opacity: 0.05 + (Math.abs(net) / maxFlow) * 0.15 }} />
                  )}
                  <span className={cn('relative flex items-baseline justify-between gap-1 text-xs tabular', key === today ? 'font-semibold text-fg' : 'text-muted')}>
                    {day.getDate() === 1 || key === start ? day.toLocaleDateString(locale, { day: 'numeric', month: 'short' }) : day.getDate()}
                    {note && <span className="hidden truncate text-[0.6875rem] font-medium @2xl:inline">{note(key)}</span>}
                  </span>
                  {f && net !== 0 && formatFlow && (
                    <span aria-hidden className={cn('relative truncate text-[0.625rem] leading-tight font-semibold tabular @2xl:hidden', net > 0 ? 'text-[var(--flow-in)]' : 'text-[var(--flow-out)]')}>
                      {net > 0 ? '+' : '−'}{formatFlow(Math.abs(net))}
                    </span>
                  )}
                  {f && (f.in > 0 || f.out > 0) && formatFlow && (
                    <span className="relative hidden flex-col items-end text-[0.6875rem] leading-tight font-medium tabular @2xl:flex" data-testid={`flow-${key}`}>
                      {f.in > 0 && <span className="text-[var(--flow-in)]">+{formatFlow(f.in)}</span>}
                      {f.out > 0 && <span className="text-[var(--flow-out)]">−{formatFlow(f.out)}</span>}
                    </span>
                  )}
                  {items.length > 0 && (
                    <span aria-hidden className="relative mt-auto flex flex-wrap gap-0.5 @2xl:hidden">
                      {items.slice(0, 6).map((x) => (
                        <span key={x.id} className={cn('size-1.5 rounded-full', x.tone === 'done' || x.tone === 'good' ? 'bg-good' : x.tone === 'critical' ? 'bg-critical' : 'bg-fg/50')} />
                      ))}
                    </span>
                  )}
                  {shown.map((x) => (
                    <button type="button" key={x.id} title={x.title ?? x.label}
                      // A chip opens its item at once; the rest of the day opens the menu.
                      onPointerDown={(e) => e.stopPropagation()} onKeyDown={(e) => e.stopPropagation()}
                      onClick={(e) => { e.stopPropagation(); x.onOpen?.() }} aria-label={`Open ${x.label}`}
                      className={cn('relative hidden truncate rounded px-1 py-0.5 text-left @2xl:block text-[0.6875rem] leading-tight hover:ring-1 hover:ring-border-strong',
                        x.tone === 'done' ? 'bg-good/10 text-good-text line-through decoration-1' : x.tone === 'good' ? 'bg-good/10 text-good-text'
                          : x.tone === 'critical' ? 'bg-critical/10 text-critical' : 'bg-surface-2 text-fg')}>
                      {x.label}
                    </button>
                  ))}
                  {items.length > shown.length && <span className="relative hidden text-[0.6875rem] text-subtle @2xl:inline">+{items.length - shown.length} more</span>}
                </div>
              </DropdownMenu.Trigger>
              <DropdownMenu.Portal>
                <DropdownMenu.Content align="start" sideOffset={4} aria-label={dayName}
                  className="z-50 max-h-[60vh] min-w-60 overflow-y-auto rounded-control border border-border bg-surface p-1 shadow-lift data-[state=open]:animate-[pop-in_120ms_ease-out]">
                  <DropdownMenu.Label className="px-2.5 pt-2 pb-1 text-[0.6875rem] font-medium tracking-wide text-subtle uppercase">{dayName}</DropdownMenu.Label>
                  {items.map((x) => (
                    <DropdownMenu.Item key={x.id} className={menuItem} onSelect={() => x.onOpen?.()}>
                      {x.menuLabel ?? <span className="truncate">{x.title ?? x.label}</span>}
                    </DropdownMenu.Item>
                  ))}
                  {items.length > 0 && <DropdownMenu.Separator className="my-1 h-px bg-border" />}
                  {actions(key, key <= today).map((a) => a.asChild ? (
                    <DropdownMenu.Item key={a.key} className={menuItem} asChild>{a.asChild}</DropdownMenu.Item>
                  ) : (
                    <DropdownMenu.Item key={a.key} className={menuItem} onSelect={a.onSelect}>{a.icon}{a.label}</DropdownMenu.Item>
                  ))}
                </DropdownMenu.Content>
              </DropdownMenu.Portal>
            </DropdownMenu.Root>
          )
        })}
      </div>
    </div>
    {flows && (
      <p className="flex flex-wrap items-center gap-x-4 gap-y-1 px-1 text-xs text-muted">
        <span className="flex items-center gap-1.5"><span className="size-2.5 rounded-[3px] bg-[var(--flow-in)]" />Money in</span>
        <span className="flex items-center gap-1.5"><span className="size-2.5 rounded-[3px] bg-[var(--flow-out)]" />Money out</span>
        <span className="text-subtle">A day's colour shows which was more. Tap a day to see it.</span>
      </p>
    )}
    </div>
  )
}

export const menuItem = 'flex cursor-pointer select-none items-center gap-2 rounded-[calc(var(--radius-sm)-3px)] px-2.5 py-2 text-sm outline-none data-[highlighted]:bg-surface-2 [&_svg]:size-4'

export function parse(isoDate: string): Date {
  const [y, m, d] = isoDate.split('-').map(Number)
  return new Date(y!, m! - 1, d!)
}

export function isoDay(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** The calendar month containing a day, and the months either side, as yyyy-mm-dd bounds. */
export function monthBounds(month: string): { start: string; end: string; prev: string; next: string } {
  const [y, m] = month.split('-').map(Number)
  const end = new Date(y!, m!, 0)
  const prev = new Date(y!, m! - 2, 1)
  const next = new Date(y!, m!, 1)
  return { start: `${month.slice(0, 7)}-01`, end: isoDay(end), prev: isoDay(prev).slice(0, 7), next: isoDay(next).slice(0, 7) }
}
