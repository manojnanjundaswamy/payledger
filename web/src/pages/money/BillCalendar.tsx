import { Link } from '@tanstack/react-router'
import { CalendarPlus, Pencil, Receipt } from 'lucide-react'
import { MonthCalendar, menuItem } from '@/components/MonthCalendar'
import { daysUntil, type Due } from '@/state/money'

/**
 * The budget month as a calendar (from the market scan: Monarch's bill calendar) with what is due each day. Paid items
 * are ticked, overdue ones are red; marking paid stays in the list view. Clicking a day offers to add a bill on it, or
 * to record an expense on it.
 */
export function BillCalendar({ start, end, due, money, locale, onOpen, onAdd }: {
  start: string; end: string; due: Due[]; money: (m: number) => string; locale: string
  /** Opens a bill or saving when its chip is clicked. */
  onOpen?: (commitmentId: string) => void
  /** Adds a bill or saving first due on that day. */
  onAdd?: (date: string) => void
}) {
  return (
    <MonthCalendar start={start} end={end} locale={locale} maxChips={4} label="Bills and savings calendar"
      chips={(day) => due.filter((x) => x.dueDate === day).map((x) => {
        const overdue = !x.paid && daysUntil(x.dueDate) < 0
        return {
          id: x.commitmentId,
          label: `${x.paid ? '✓ ' : ''}${x.name} · ${money(x.amount)}`,
          title: `${x.name}: ${money(x.amount)}${x.paid ? ', paid' : overdue ? ', overdue' : ''}`,
          tone: x.paid ? 'done' : overdue ? 'critical' : 'default',
          onOpen: () => onOpen?.(x.commitmentId),
          menuLabel: <><Pencil />Edit {x.name}<span className="ml-auto pl-3 text-xs text-subtle tabular">{money(x.amount)}</span></>,
        }
      })}
      actions={(date, past) => [
        { key: 'add', icon: <CalendarPlus />, label: 'Add a bill or saving due this day', onSelect: () => onAdd?.(date) },
        ...(past ? [{ key: 'expense', label: '', asChild: <Link to="/transactions" search={{ add: 'EXPENSE', date }} className={menuItem}><Receipt />Record an expense on this day</Link> }] : []),
      ]}
    />
  )
}
