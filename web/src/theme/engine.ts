import { accentFor, readableOn } from '@/lib/color'
import type { Theme } from '@/state/preferences'

const PAGE = { light: '#ffffff', dark: '#0a0a0a' } as const
const RADIUS = { sharp: ['4px', '3px'], soft: ['14px', '9px'], round: ['22px', '14px'] } as const
const DENSITY = { comfortable: ['1.25rem', '1rem'], compact: ['0.875rem', '0.625rem'] } as const
const TEXT = { sm: '15px', md: '16px', lg: '17.5px' } as const
const CACHE_KEY = 'payledger.theme'

export type ResolvedMode = 'light' | 'dark'

export function resolveMode(mode: Theme['mode']): ResolvedMode {
  if (mode !== 'system') return mode
  return typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

/** The CSS variables a theme sets on :root. Pure, so it is easy to test and to cache. */
export function themeVars(theme: Theme, mode: ResolvedMode): Record<string, string> {
  const vars: Record<string, string> = {
    '--radius': RADIUS[theme.radius][0],
    '--radius-sm': RADIUS[theme.radius][1],
    '--pad': DENSITY[theme.density][0],
    '--gap': DENSITY[theme.density][1],
    'font-size': TEXT[theme.textSize],
  }
  if (theme.accent) {
    const accent = accentFor(theme.accent, PAGE[mode])
    vars['--accent'] = accent
    vars['--accent-fg'] = readableOn(accent)
  }
  return vars
}

const MANAGED = ['--radius', '--radius-sm', '--pad', '--gap', 'font-size', '--accent', '--accent-fg']

/** Writes the theme onto <html>; also caches it so index.html can paint the right theme before React loads. */
export function applyTheme(theme: Theme): ResolvedMode {
  const root = document.documentElement
  const mode = resolveMode(theme.mode)
  const vars = themeVars(theme, mode)
  root.dataset.theme = mode
  root.dataset.motion = theme.motion
  root.dataset.width = theme.width
  for (const name of MANAGED) {
    if (vars[name]) root.style.setProperty(name, vars[name])
    else root.style.removeProperty(name)
  }
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', mode === 'dark' ? '#0a0a0a' : '#ffffff')
  try {
    localStorage.setItem(CACHE_KEY, JSON.stringify({ mode: theme.mode, vars, width: theme.width }))
  } catch {
    // private mode or blocked storage: the theme still applies, it just isn't pre-painted next time
  }
  return mode
}

export function prefersReducedMotion(theme: Theme): boolean {
  if (theme.motion === 'reduced') return true
  if (theme.motion === 'full') return false
  return typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches
}
