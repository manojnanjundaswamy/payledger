import { describe, expect, it } from 'vitest'
import { parsePreferences } from './preferences'

describe('parsePreferences', () => {
  it('gives a new user a monochrome theme and the overview dashboard', () => {
    const prefs = parsePreferences({})
    expect(prefs.theme).toEqual({ mode: 'system', accent: null, palette: 'classic', radius: 'soft', density: 'comfortable', textSize: 'md', motion: 'system', width: 'comfortable' })
    expect(prefs.dashboards).toHaveLength(1)
    expect(prefs.dashboards[0]!.name).toBe('Overview')
    expect(prefs.activeDashboard).toBe(prefs.dashboards[0]!.id)
    expect(prefs.sampleData).toBe(true)
  })

  it('keeps a known palette and falls back to classic for anything else', () => {
    expect(parsePreferences({ theme: { palette: 'ferrari' } }).theme.palette).toBe('ferrari')
    expect(parsePreferences({ theme: { palette: 'lamborghini' } }).theme.palette).toBe('classic')
  })

  it('keeps what is valid and repairs what is not, field by field', () => {
    const prefs = parsePreferences({
      theme: { mode: 'dark', accent: 'not-a-colour', radius: 'round', density: 'huge' },
      dashboards: [
        { id: 'd1', name: 'Mine', widgets: [
          { id: 'a', type: 'budgets', size: 'l' },
          { id: 'b', type: 'fromTheFuture', size: 'm' },
          { id: 'c', type: 'note', size: 'gigantic', text: 'Pay rent' },
        ] },
      ],
      activeDashboard: 'missing',
    })
    expect(prefs.theme.mode).toBe('dark')
    expect(prefs.theme.accent).toBeNull()
    expect(prefs.theme.radius).toBe('round')
    expect(prefs.theme.density).toBe('comfortable')
    expect(prefs.dashboards[0]!.widgets.map((w) => [w.id, w.size])).toEqual([['a', 'l'], ['c', 'm']])
    expect(prefs.activeDashboard).toBe('d1')
  })

  it('normalises accent colours', () => {
    expect(parsePreferences({ theme: { accent: '#4F46E5' } }).theme.accent).toBe('#4f46e5')
  })

  it('survives garbage', () => {
    expect(() => parsePreferences('nope')).not.toThrow()
    expect(() => parsePreferences(null)).not.toThrow()
    expect(parsePreferences({ dashboards: 'x' }).dashboards).toHaveLength(1)
  })
})

describe('page width', () => {
  it('is comfortable unless set, and survives a bad value', () => {
    expect(parsePreferences({ theme: { width: 'wide' } }).theme.width).toBe('wide')
    expect(parsePreferences({ theme: { width: 'huge' } }).theme.width).toBe('comfortable')
  })
})
