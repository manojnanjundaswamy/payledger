import { budgetPeriod } from '@/dashboard/sample'

/** A date range for reports, both ends included (yyyy-mm-dd). */
export interface Period { key: PeriodKey; from: string; to: string; label: string }

export const PERIOD_KEYS = ['this-month', 'last-month', 'this-fy', 'last-fy', 'last-12'] as const
export type PeriodKey = (typeof PERIOD_KEYS)[number] | 'custom'

export function iso(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function parse(isoDate: string): Date {
  const [y, m, d] = isoDate.split('-').map(Number)
  return new Date(y!, m! - 1, d!)
}

/** India's financial year, April to March: "FY 2026–27" runs from 1 Apr 2026 to 31 Mar 2027. */
export function financialYear(day: Date): { start: Date; end: Date; label: string } {
  const startYear = day.getMonth() >= 3 ? day.getFullYear() : day.getFullYear() - 1
  return {
    start: new Date(startYear, 3, 1),
    end: new Date(startYear + 1, 2, 31),
    label: `FY ${startYear}–${String((startYear + 1) % 100).padStart(2, '0')}`,
  }
}

/**
 * The named periods. "This month" is the budget month (it may start on payday); the financial years
 * and the last 12 months are calendar ranges, as tax and bank statements use.
 */
export function reportPeriod(key: PeriodKey, budgetStartDay: number, today = new Date(), custom?: { from?: string; to?: string }): Period {
  switch (key) {
    case 'this-month': {
      const p = budgetPeriod(budgetStartDay, today)
      return { key, from: iso(p.start), to: iso(p.end), label: 'This month' }
    }
    case 'last-month': {
      const p = budgetPeriod(budgetStartDay, today)
      const before = budgetPeriod(budgetStartDay, new Date(p.start.getFullYear(), p.start.getMonth(), p.start.getDate() - 1))
      return { key, from: iso(before.start), to: iso(before.end), label: 'Last month' }
    }
    case 'this-fy': {
      const fy = financialYear(today)
      return { key, from: iso(fy.start), to: iso(fy.end), label: `This year (${fy.label})` }
    }
    case 'last-fy': {
      const fy = financialYear(new Date(today.getFullYear() - 1, today.getMonth(), 1))
      return { key, from: iso(fy.start), to: iso(fy.end), label: `Last year (${fy.label})` }
    }
    case 'last-12': {
      return {
        key,
        from: iso(new Date(today.getFullYear(), today.getMonth() - 11, 1)),
        to: iso(new Date(today.getFullYear(), today.getMonth() + 1, 0)),
        label: 'Last 12 months',
      }
    }
    case 'custom': {
      const from = custom?.from ?? iso(new Date(today.getFullYear(), today.getMonth(), 1))
      const to = custom?.to && custom.to >= from ? custom.to : from
      return { key, from, to, label: 'Custom' }
    }
  }
}

/** First days of the calendar months the range touches, oldest first ("2026-04-01", …). */
export function monthsIn(from: string, to: string): string[] {
  const start = parse(from)
  const end = parse(to)
  const months: string[] = []
  for (let d = new Date(start.getFullYear(), start.getMonth(), 1); d <= end; d = new Date(d.getFullYear(), d.getMonth() + 1, 1)) {
    months.push(iso(d))
  }
  return months
}
