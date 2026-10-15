import { z } from 'zod'

/**
 * Everything the user customises, stored as one JSON object on their profile
 * (core-service `users.preferences`). Parsing is TOLERANT: an unknown or broken field falls back
 * to its default instead of failing, so an old or hand-edited value can never break the app.
 */

export const WIDGET_TYPES = [
  'safeToSpend', 'budgets', 'spendingByCategory', 'cashFlow', 'recentTransactions',
  'upcomingBills', 'groups', 'goals', 'household', 'gettingStarted', 'quickActions', 'note', 'toReview', 'netWorth',
] as const
export type WidgetType = (typeof WIDGET_TYPES)[number]

export const WIDGET_SIZES = ['s', 'm', 'l', 'xl'] as const
export type WidgetSize = (typeof WIDGET_SIZES)[number]

export const ACCENT_PRESETS = [
  { name: 'Monochrome', value: null },
  { name: 'Indigo', value: '#4f46e5' },
  { name: 'Emerald', value: '#059669' },
  { name: 'Saffron', value: '#f97316' },
  { name: 'Rose', value: '#e11d48' },
  { name: 'Teal', value: '#0d9488' },
  { name: 'Violet', value: '#7c3aed' },
  { name: 'Amber', value: '#d97706' },
  { name: 'Sky', value: '#0284c7' },
] as const

/**
 * Whole palettes, beyond an accent: they tint the page, cards and borders too, in light and dark (styles.css,
 * [data-palette]). Classic is the black-and-white default.
 */
export const PALETTES = [
  { id: 'classic', name: 'Classic', description: 'Black and white', swatches: ['#ffffff', '#f6f6f5', '#0a0a0a'] },
  { id: 'bumblebee', name: 'Bumblebee yellow', description: 'Warm yellow and black, with a hazard stripe', swatches: ['#fffcf0', '#ffc800', '#14120b'] },
  { id: 'ferrari', name: 'Ferrari orange', description: 'Race orange on carbon, with a racing stripe', swatches: ['#fff8f4', '#ea5000', '#1a0e09'] },
] as const
export type PaletteId = (typeof PALETTES)[number]['id']

const hex = z.string().regex(/^#[0-9a-fA-F]{6}$/).transform((s) => s.toLowerCase())

export const ThemeSchema = z.object({
  mode: z.enum(['system', 'light', 'dark']).catch('system'),
  /** null = monochrome (black on white / white on black), or the palette's own accent */
  accent: hex.nullable().catch(null),
  palette: z.enum(['classic', 'bumblebee', 'ferrari']).catch('classic'),
  radius: z.enum(['sharp', 'soft', 'round']).catch('soft'),
  density: z.enum(['comfortable', 'compact']).catch('comfortable'),
  textSize: z.enum(['sm', 'md', 'lg']).catch('md'),
  motion: z.enum(['system', 'reduced', 'full']).catch('system'),
  /** comfortable: pages in a readable column; wide: pages use the whole window, for lists, calendars and charts. */
  width: z.enum(['comfortable', 'wide']).catch('comfortable'),
})
export type Theme = z.infer<typeof ThemeSchema>

export const WidgetSchema = z.object({
  id: z.string().min(1).max(64),
  type: z.enum(WIDGET_TYPES),
  size: z.enum(WIDGET_SIZES).catch('m'),
  title: z.string().max(60).optional().catch(undefined),
  /** free text for the note widget */
  text: z.string().max(1000).optional().catch(undefined),
})
export type Widget = z.infer<typeof WidgetSchema>

export const DashboardSchema = z.object({
  id: z.string().min(1).max(64),
  name: z.string().min(1).max(40).catch('Dashboard'),
  /** widgets of unknown types (from a newer version) are dropped, not fatal */
  widgets: z.array(z.unknown()).catch([]).transform((items) =>
    items.flatMap((w) => {
      const parsed = WidgetSchema.safeParse(w)
      return parsed.success ? [parsed.data] : []
    }).slice(0, 40)),
})
export type Dashboard = z.infer<typeof DashboardSchema>

export function defaultTheme(): Theme {
  return { mode: 'system', accent: null, palette: 'classic', radius: 'soft', density: 'comfortable', textSize: 'md', motion: 'system', width: 'comfortable' }
}

let counter = 0
export function newId(prefix: string): string {
  counter += 1
  return `${prefix}-${Date.now().toString(36)}${counter.toString(36)}${Math.random().toString(36).slice(2, 6)}`
}

export const DASHBOARD_TEMPLATES = {
  overview: { name: 'Overview', widgets: [
    ['gettingStarted', 'm'], ['safeToSpend', 'm'], ['budgets', 'm'], ['spendingByCategory', 'm'],
    ['cashFlow', 'l'], ['upcomingBills', 's'], ['recentTransactions', 'm'], ['groups', 'm'], ['netWorth', 'm'], ['toReview', 's'],
  ] },
  household: { name: 'Household', widgets: [
    ['household', 'l'], ['budgets', 's'], ['upcomingBills', 'm'], ['spendingByCategory', 'm'], ['goals', 'm'],
  ] },
  splitting: { name: 'Groups & trips', widgets: [['groups', 'm'], ['recentTransactions', 'm'], ['quickActions', 'm'], ['note', 'm']] },
  blank: { name: 'My dashboard', widgets: [] },
} satisfies Record<string, { name: string; widgets: [WidgetType, WidgetSize][] }>

export type TemplateKey = keyof typeof DASHBOARD_TEMPLATES

export function dashboardFromTemplate(key: TemplateKey, name?: string): Dashboard {
  const t = DASHBOARD_TEMPLATES[key]
  return {
    id: newId('dash'),
    name: name ?? t.name,
    widgets: t.widgets.map(([type, size]) => ({ id: newId('w'), type, size })),
  }
}

export const PreferencesSchema = z.object({
  version: z.literal(1).catch(1),
  theme: ThemeSchema.catch(defaultTheme()),
  dashboards: z.array(z.unknown()).catch([]).transform((items) =>
    items.flatMap((d) => {
      const parsed = DashboardSchema.safeParse(d)
      return parsed.success ? [parsed.data] : []
    }).slice(0, 12)),
  activeDashboard: z.string().optional().catch(undefined),
  /** Show realistic sample numbers until the user has real data (Phase 4). */
  sampleData: z.boolean().catch(true),
  sidebarCollapsed: z.boolean().catch(false),
  /** "Make it yours" (look and feel) has been shown once. */
  welcomed: z.boolean().catch(false),
  /** "Don't show the tour again" was ticked; otherwise it's offered at each sign-in. */
  tourOff: z.boolean().catch(false),
})
export type Preferences = Omit<z.infer<typeof PreferencesSchema>, 'activeDashboard'> & { activeDashboard: string }

export function parsePreferences(raw: unknown): Preferences {
  const parsed = PreferencesSchema.parse(raw && typeof raw === 'object' ? raw : {})
  const dashboards = parsed.dashboards.length > 0 ? parsed.dashboards : [dashboardFromTemplate('overview')]
  const active = dashboards.some((d) => d.id === parsed.activeDashboard) ? parsed.activeDashboard! : dashboards[0]!.id
  return { ...parsed, dashboards, activeDashboard: active }
}
