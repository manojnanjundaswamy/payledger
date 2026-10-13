import { createContext, useContext } from 'react'
import type { Preferences } from '@/state/preferences'
import type { Profile } from '@/state/profile'

export interface WidgetEnv {
  /** Show the sample numbers (only while the user has no accounts of their own). */
  sample: boolean
  /** The user has at least one account: widgets that can, show their real data. */
  real: boolean
  profile: Profile
  prefs: Preferences
  money: (minor: number, opts?: { sign?: boolean; compact?: boolean; whole?: boolean }) => string
}

export const WidgetEnvContext = createContext<WidgetEnv | null>(null)

export function useWidgetEnv(): WidgetEnv {
  const env = useContext(WidgetEnvContext)
  if (!env) throw new Error('widget rendered outside a dashboard')
  return env
}
