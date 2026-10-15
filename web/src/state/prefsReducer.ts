import {
  dashboardFromTemplate, newId,
  type Dashboard, type Preferences, type TemplateKey, type Theme, type Widget, type WidgetSize, type WidgetType,
} from './preferences'

export type PrefsAction =
  | { type: 'theme'; patch: Partial<Theme> }
  | { type: 'sampleData'; value: boolean }
  | { type: 'sidebar'; collapsed: boolean }
  | { type: 'onboarding'; patch: Partial<Pick<Preferences, 'welcomed' | 'tourOff'>> }
  | { type: 'selectDashboard'; id: string }
  | { type: 'addDashboard'; template: TemplateKey; name?: string }
  | { type: 'renameDashboard'; id: string; name: string }
  | { type: 'removeDashboard'; id: string }
  | { type: 'resetDashboard'; id: string }
  | { type: 'moveWidget'; dashboard: string; from: string; to: string }
  | { type: 'addWidget'; dashboard: string; widget: WidgetType; size: WidgetSize }
  | { type: 'removeWidget'; dashboard: string; id: string }
  | { type: 'updateWidget'; dashboard: string; id: string; patch: Partial<Pick<Widget, 'size' | 'title' | 'text'>> }
  | { type: 'restore'; prefs: Preferences }
  | { type: 'resetAll'; prefs: Preferences }

const MAX_DASHBOARDS = 12
const MAX_WIDGETS = 40

function mapDashboard(prefs: Preferences, id: string, fn: (d: Dashboard) => Dashboard): Preferences {
  return { ...prefs, dashboards: prefs.dashboards.map((d) => (d.id === id ? fn(d) : d)) }
}

export function prefsReducer(prefs: Preferences, action: PrefsAction): Preferences {
  switch (action.type) {
    case 'theme':
      return { ...prefs, theme: { ...prefs.theme, ...action.patch } }
    case 'sampleData':
      return { ...prefs, sampleData: action.value }
    case 'sidebar':
      return { ...prefs, sidebarCollapsed: action.collapsed }
    case 'onboarding':
      return { ...prefs, welcomed: action.patch.welcomed ?? prefs.welcomed, tourOff: action.patch.tourOff ?? prefs.tourOff }
    case 'selectDashboard':
      return prefs.dashboards.some((d) => d.id === action.id) ? { ...prefs, activeDashboard: action.id } : prefs
    case 'addDashboard': {
      if (prefs.dashboards.length >= MAX_DASHBOARDS) return prefs
      const dashboard = dashboardFromTemplate(action.template, action.name?.trim() || undefined)
      return { ...prefs, dashboards: [...prefs.dashboards, dashboard], activeDashboard: dashboard.id }
    }
    case 'renameDashboard': {
      const name = action.name.trim().slice(0, 40)
      return name ? mapDashboard(prefs, action.id, (d) => ({ ...d, name })) : prefs
    }
    case 'removeDashboard': {
      if (prefs.dashboards.length <= 1) return prefs
      const index = prefs.dashboards.findIndex((d) => d.id === action.id)
      if (index < 0) return prefs
      const dashboards = prefs.dashboards.filter((d) => d.id !== action.id)
      const active = prefs.activeDashboard === action.id ? dashboards[Math.max(0, index - 1)]!.id : prefs.activeDashboard
      return { ...prefs, dashboards, activeDashboard: active }
    }
    case 'resetDashboard':
      return mapDashboard(prefs, action.id, (d) => ({ ...dashboardFromTemplate('overview'), id: d.id, name: d.name }))
    case 'moveWidget':
      return mapDashboard(prefs, action.dashboard, (d) => {
        const from = d.widgets.findIndex((w) => w.id === action.from)
        const to = d.widgets.findIndex((w) => w.id === action.to)
        if (from < 0 || to < 0 || from === to) return d
        const widgets = [...d.widgets]
        const [moved] = widgets.splice(from, 1)
        widgets.splice(to, 0, moved!)
        return { ...d, widgets }
      })
    case 'addWidget':
      return mapDashboard(prefs, action.dashboard, (d) =>
        d.widgets.length >= MAX_WIDGETS ? d : { ...d, widgets: [{ id: newId('w'), type: action.widget, size: action.size }, ...d.widgets] })
    case 'removeWidget':
      return mapDashboard(prefs, action.dashboard, (d) => ({ ...d, widgets: d.widgets.filter((w) => w.id !== action.id) }))
    case 'updateWidget':
      return mapDashboard(prefs, action.dashboard, (d) => ({
        ...d,
        widgets: d.widgets.map((w) => {
          if (w.id !== action.id) return w
          const next = { ...w, ...action.patch }
          if (next.title !== undefined && !next.title.trim()) delete next.title
          return next
        }),
      }))
    case 'restore':
    case 'resetAll':
      return action.prefs
  }
}
