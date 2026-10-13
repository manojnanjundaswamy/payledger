import { describe, expect, it } from 'vitest'
import { previewSplit } from './groups'
import { categoryLabel, categoryTree, daysUntil, everyLabel, problemsCsv, shiftMonth, type Category, type ImportRow } from './money'

const cat = (id: string, name: string, parentId: string | null = null): Category =>
  ({ id, kind: 'EXPENSE', name, icon: 'tag', color: 'series-1', parentId, hidden: false })

describe('categoryTree', () => {
  it('nests subcategories under their parent and keeps the order', () => {
    const food = cat('f', 'Food')
    const rent = cat('r', 'Rent')
    const snacks = cat('s', 'Snacks', 'f')
    const tiffin = cat('t', 'Tiffin', 'f')
    expect(categoryTree([food, snacks, rent, tiffin])).toEqual([
      { category: food, children: [snacks, tiffin] },
      { category: rent, children: [] },
    ])
  })

  it('shows a subcategory whose parent is missing at the top level', () => {
    const stray = cat('s', 'Snacks', 'gone')
    expect(categoryTree([stray])).toEqual([{ category: stray, children: [] }])
  })
})

describe('categoryLabel', () => {
  it('prefixes the parent', () => {
    const all = [cat('f', 'Food'), cat('s', 'Snacks', 'f')]
    expect(categoryLabel(all[1]!, all)).toBe('Food › Snacks')
    expect(categoryLabel(all[0]!, all)).toBe('Food')
  })
})

describe('shiftMonth', () => {
  it('crosses year boundaries both ways', () => {
    expect(shiftMonth('2026-12', 1)).toBe('2027-01')
    expect(shiftMonth('2026-01', -1)).toBe('2025-12')
    expect(shiftMonth('2026-10', -3)).toBe('2026-07')
  })
})

describe('daysUntil', () => {
  it('counts whole days from today, ignoring the time', () => {
    const today = new Date(2026, 9, 13, 23, 30)
    expect(daysUntil('2026-10-13', today)).toBe(0)
    expect(daysUntil('2026-10-16', today)).toBe(3)
    expect(daysUntil('2026-10-10', today)).toBe(-3)
    expect(daysUntil('2026-11-01', today)).toBe(19)
  })
})

describe('everyLabel', () => {
  it('reads naturally', () => {
    expect(everyLabel(1)).toBe('Every month')
    expect(everyLabel(3)).toBe('Every 3 months')
    expect(everyLabel(12)).toBe('Every year')
  })
})

describe('problemsCsv', () => {
  it('lists every problem with its row and quotes cells that need it', () => {
    const row = (line: number, problems: ImportRow['problems']): ImportRow => ({
      line, status: problems.length ? 'ERROR' : 'READY', problems, kind: null, amount: null, date: null,
      account: '', toAccount: null, category: null, description: '',
    })
    expect(problemsCsv([
      row(2, []),
      row(3, [{ line: 3, column: 'date', reason: 'not a date: use DD-MM-YYYY' }, { line: 3, column: 'amount', reason: 'missing' }]),
      row(5, [{ line: 5, column: 'account', reason: 'no account called "ICICI, card"' }]),
    ])).toBe('row,column,reason\r\n3,date,not a date: use DD-MM-YYYY\r\n3,amount,missing\r\n5,account,"no account called ""ICICI, card"""\r\n')
  })
})

describe('previewSplit', () => {
  it('matches the server: leftover paise go one each from the top', () => {
    expect(previewSplit(10_000, 'EQUAL', [{ memberId: 'a', value: 0 }, { memberId: 'b', value: 0 }, { memberId: 'c', value: 0 }]))
      .toEqual({ a: 3_334, b: 3_333, c: 3_333 })
    expect(previewSplit(9_999, 'PERCENT', [{ memberId: 'a', value: 5_000 }, { memberId: 'b', value: 3_000 }, { memberId: 'c', value: 2_000 }]))
      .toEqual({ a: 5_000, b: 3_000, c: 1_999 })
    expect(previewSplit(1_000, 'SHARES', [{ memberId: 'a', value: 2 }, { memberId: 'b', value: 1 }, { memberId: 'c', value: 0 }]))
      .toEqual({ a: 667, b: 333, c: 0 })
    expect(previewSplit(1_000, 'PERCENT', [{ memberId: 'a', value: 5_000 }])).toBeNull()
  })
})
