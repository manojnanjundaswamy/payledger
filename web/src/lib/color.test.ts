import { describe, expect, it } from 'vitest'
import { accentFor, contrast, readableOn } from './color'

describe('colour helpers', () => {
  it('computes WCAG contrast', () => {
    expect(contrast('#000000', '#ffffff')).toBeCloseTo(21, 0)
    expect(contrast('#777777', '#ffffff')).toBeCloseTo(4.48, 1)
  })

  it('picks a readable text colour for any accent', () => {
    expect(readableOn('#fde047')).toBe('#0a0a0a')
    expect(readableOn('#1e3a8a')).toBe('#ffffff')
  })

  it('deepens a pale accent on white and lightens a dark one on black until it reaches 3:1', () => {
    const paleOnLight = accentFor('#fde047', '#ffffff')
    expect(contrast(paleOnLight, '#ffffff')).toBeGreaterThanOrEqual(3)
    const darkOnDark = accentFor('#1e1b4b', '#0a0a0a')
    expect(contrast(darkOnDark, '#0a0a0a')).toBeGreaterThanOrEqual(3)
    expect(accentFor('#4f46e5', '#ffffff')).toBe('#4f46e5') // already fine: untouched
  })
})
