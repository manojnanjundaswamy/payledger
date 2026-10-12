import { describe, expect, it } from 'vitest'
import { parsePreferences, type Preferences } from './preferences'
import { prefsReducer } from './prefsReducer'

function start(): Preferences {
  return parsePreferences({
    dashboards: [{ id: 'd1', name: 'Overview', widgets: [
      { id: 'a', type: 'budgets', size: 'm' }, { id: 'b', type: 'goals', size: 's' }, { id: 'c', type: 'note', size: 'm' },
    ] }],
  })
}
const ids = (p: Preferences, dash = 'd1') => p.dashboards.find((d) => d.id === dash)!.widgets.map((w) => w.id)

describe('prefsReducer', () => {
  it('moves a widget to where it was dropped', () => {
    expect(ids(prefsReducer(start(), { type: 'moveWidget', dashboard: 'd1', from: 'a', to: 'c' }))).toEqual(['b', 'c', 'a'])
    expect(ids(prefsReducer(start(), { type: 'moveWidget', dashboard: 'd1', from: 'c', to: 'a' }))).toEqual(['c', 'a', 'b'])
  })

  it('adds new widgets at the top so they are visible, and removes them', () => {
    const added = prefsReducer(start(), { type: 'addWidget', dashboard: 'd1', widget: 'cashFlow', size: 'l' })
    expect(added.dashboards[0]!.widgets[0]).toMatchObject({ type: 'cashFlow', size: 'l' })
    expect(ids(prefsReducer(start(), { type: 'removeWidget', dashboard: 'd1', id: 'b' }))).toEqual(['a', 'c'])
  })

  it('resizes and renames, and a blank title falls back to the default', () => {
    let p = prefsReducer(start(), { type: 'updateWidget', dashboard: 'd1', id: 'a', patch: { size: 'xl', title: 'Monthly limits' } })
    expect(p.dashboards[0]!.widgets[0]).toMatchObject({ size: 'xl', title: 'Monthly limits' })
    p = prefsReducer(p, { type: 'updateWidget', dashboard: 'd1', id: 'a', patch: { title: '   ' } })
    expect(p.dashboards[0]!.widgets[0]!.title).toBeUndefined()
  })

  it('creates dashboards from templates, switches to them, and never deletes the last one', () => {
    let p = prefsReducer(start(), { type: 'addDashboard', template: 'household', name: 'Home' })
    expect(p.dashboards.map((d) => d.name)).toEqual(['Overview', 'Home'])
    expect(p.activeDashboard).toBe(p.dashboards[1]!.id)
    p = prefsReducer(p, { type: 'removeDashboard', id: p.activeDashboard })
    expect(p.activeDashboard).toBe('d1')
    expect(prefsReducer(p, { type: 'removeDashboard', id: 'd1' }).dashboards).toHaveLength(1)
  })

  it('ignores empty dashboard names', () => {
    expect(prefsReducer(start(), { type: 'renameDashboard', id: 'd1', name: '  ' }).dashboards[0]!.name).toBe('Overview')
  })
})
