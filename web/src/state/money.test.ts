import { describe, expect, it } from 'vitest'
import { categoryLabel, categoryTree, type Category } from './money'

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
