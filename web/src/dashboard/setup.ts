import { useEffect, useState } from 'react'
import { pushState, type PushState } from '@/lib/push'
import { DASHBOARD_TEMPLATES, type Preferences } from '@/state/preferences'
import { useAccounts, useBudgets } from '@/state/money'
import { useProfile } from '@/state/profile'
import { usePreferences } from '@/state/PreferencesProvider'
import type { SettingsTab } from '@/pages/settings/tabs'

/** One setup step: done from the real state of things, and where to go to do it. */
export interface SetupStep {
  key: string
  label: string
  done: boolean
  tab?: SettingsTab
  to?: '/welcome' | '/budgets'
  /** Done right here, in the card (turning on notifications). */
  action?: 'push'
}

/** A dashboard someone has changed: more than one, or one that isn't the template it started as. */
function customized(prefs: Preferences): boolean {
  if (prefs.dashboards.length > 1) return true
  const d = prefs.dashboards[0]
  if (!d) return false
  const template = DASHBOARD_TEMPLATES.overview.widgets
  return d.name !== DASHBOARD_TEMPLATES.overview.name || d.widgets.length !== template.length
    || d.widgets.some((w, i) => w.type !== template[i]?.[0] || w.size !== template[i]?.[1] || !!w.title || !!w.text)
}

/**
 * Getting started, worked out from what's actually set up, so a step ticks itself the moment it's done and the card
 * goes away once everything is. Used by the card and by the dashboard (which hides the card when it's all done).
 */
export function useSetupSteps(): { steps: SetupStep[]; done: number; complete: boolean; loading: boolean } {
  const { prefs } = usePreferences()
  const profile = useProfile().data
  const accounts = useAccounts()
  const budgets = useBudgets()
  const [push, setPush] = useState<PushState | null>(null)
  useEffect(() => {
    void pushState().then(setPush)
    // Coming back to the tab after allowing notifications elsewhere (Settings, the browser) updates it.
    const again = () => void pushState().then(setPush)
    window.addEventListener('focus', again)
    return () => window.removeEventListener('focus', again)
  }, [])
  const steps: SetupStep[] = [
    { key: 'account', label: 'Create your account', done: true },
    { key: 'region', label: 'Set your region and payday', done: !!profile?.settings.country, tab: 'regional' },
    { key: 'look', label: 'Make it yours: pick a theme', done: prefs.welcomed || prefs.theme.palette !== 'classic' || prefs.theme.mode !== 'system' || prefs.theme.accent !== null, tab: 'appearance' },
    { key: 'accounts', label: 'Add your accounts and bills (2 minutes)', done: (accounts.data ?? []).some((a) => a.kind !== 'OWED'), to: '/welcome' },
    { key: 'budget', label: 'Set your first budget', done: (budgets.data?.budgets.length ?? 0) > 0, to: '/budgets' },
    // A browser that can't do push (or blocked it on purpose) shouldn't keep the card open forever.
    { key: 'push', label: 'Turn on notifications', done: push === 'on' || push === 'unsupported' || push === 'blocked', action: 'push' },
    { key: 'dashboard', label: 'Customize your dashboard', done: customized(prefs), tab: 'dashboards' },
  ]
  const done = steps.filter((s) => s.done).length
  const loading = accounts.isPending || budgets.isPending || push === null
  return { steps, done, complete: !loading && done === steps.length, loading }
}
