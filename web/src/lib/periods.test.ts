import { describe, expect, it } from 'vitest'
import { financialYear, monthsIn, reportPeriod } from './periods'

describe('financialYear', () => {
  it('runs April to March', () => {
    expect(financialYear(new Date(2026, 9, 13))).toMatchObject({ label: 'FY 2026–27' })
    expect(financialYear(new Date(2026, 9, 13)).start).toEqual(new Date(2026, 3, 1))
    expect(financialYear(new Date(2027, 2, 31)).label).toBe('FY 2026–27')
    expect(financialYear(new Date(2027, 3, 1)).label).toBe('FY 2027–28')
    expect(financialYear(new Date(2099, 5, 1)).label).toBe('FY 2099–00')
  })
})

describe('reportPeriod', () => {
  const today = new Date(2026, 9, 13)
  it('names the usual periods', () => {
    expect(reportPeriod('this-fy', 1, today)).toMatchObject({ from: '2026-04-01', to: '2027-03-31', label: 'This year (FY 2026–27)' })
    expect(reportPeriod('last-fy', 1, today)).toMatchObject({ from: '2025-04-01', to: '2026-03-31' })
    expect(reportPeriod('last-12', 1, today)).toMatchObject({ from: '2025-11-01', to: '2026-10-31' })
  })
  it('follows the budget month, which can start on payday', () => {
    expect(reportPeriod('this-month', 25, today)).toMatchObject({ from: '2026-09-25', to: '2026-10-24' })
    expect(reportPeriod('last-month', 25, today)).toMatchObject({ from: '2026-08-25', to: '2026-09-24' })
    expect(reportPeriod('last-month', 1, today)).toMatchObject({ from: '2026-09-01', to: '2026-09-30' })
  })
  it('keeps a custom range in order', () => {
    expect(reportPeriod('custom', 1, today, { from: '2026-05-10', to: '2026-04-01' })).toMatchObject({ from: '2026-05-10', to: '2026-05-10' })
  })
})

describe('monthsIn', () => {
  it('lists every calendar month the range touches', () => {
    expect(monthsIn('2026-09-25', '2026-10-24')).toEqual(['2026-09-01', '2026-10-01'])
    expect(monthsIn('2026-04-01', '2027-03-31')).toHaveLength(12)
  })
})

describe('financialYear by region', () => {
  it('follows the month the year starts in', () => {
    expect(financialYear(new Date(2026, 9, 13), 1)).toMatchObject({ label: '2026', start: new Date(2026, 0, 1), end: new Date(2026, 11, 31) })
    const au = financialYear(new Date(2026, 9, 13), 7)
    expect(au.label).toBe('FY 2026–27')
    expect(au.start).toEqual(new Date(2026, 6, 1))
    expect(au.end).toEqual(new Date(2027, 5, 30))
    expect(financialYear(new Date(2026, 5, 30), 7).label).toBe('FY 2025–26')
    expect(reportPeriod('this-fy', 1, new Date(2026, 9, 13), undefined, 1)).toMatchObject({ from: '2026-01-01', to: '2026-12-31' })
  })
})
