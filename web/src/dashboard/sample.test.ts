import { describe, expect, it } from 'vitest'
import { budgetPeriod } from './sample'

describe('budgetPeriod', () => {
  it('runs from the start day to the day before it next month', () => {
    const p = budgetPeriod(25, new Date(2026, 9, 12)) // 12 Oct, payday the 25th
    expect(p.start).toEqual(new Date(2026, 8, 25))
    expect(p.end).toEqual(new Date(2026, 9, 24))
    expect(p.daysTotal).toBe(30)
    expect(p.daysLeft).toBe(13)
  })

  it('is the calendar month for start day 1', () => {
    const p = budgetPeriod(1, new Date(2026, 1, 28))
    expect(p.daysTotal).toBe(28)
    expect(p.daysLeft).toBe(1)
  })
})
