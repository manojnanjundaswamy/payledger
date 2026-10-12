import { describe, expect, it } from 'vitest'
import { contrast } from '@/lib/color'
import { defaultTheme } from '@/state/preferences'
import { themeVars } from './engine'

describe('themeVars', () => {
  it('is monochrome by default: no accent variables, so ink-on-paper applies', () => {
    const vars = themeVars(defaultTheme(), 'light')
    expect(vars['--accent']).toBeUndefined()
    expect(vars['--radius']).toBe('14px')
  })

  it('keeps a chosen accent readable in both modes, with readable text on it', () => {
    const theme = { ...defaultTheme(), accent: '#facc15' }
    const light = themeVars(theme, 'light')
    const dark = themeVars(theme, 'dark')
    expect(contrast(light['--accent']!, '#ffffff')).toBeGreaterThanOrEqual(3)
    expect(contrast(dark['--accent']!, '#0a0a0a')).toBeGreaterThanOrEqual(3)
    expect(contrast(light['--accent']!, light['--accent-fg']!)).toBeGreaterThanOrEqual(4.5)
  })

  it('maps density, radius and text size', () => {
    const vars = themeVars({ ...defaultTheme(), density: 'compact', radius: 'sharp', textSize: 'lg' }, 'dark')
    expect(vars).toMatchObject({ '--pad': '0.875rem', '--radius': '4px', 'font-size': '17.5px' })
  })
})
