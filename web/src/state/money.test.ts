import { describe, expect, it } from 'vitest'
import { categoryLabel, categoryTree, daysUntil, everyLabel, shiftMonth, type Category } from './money'

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
