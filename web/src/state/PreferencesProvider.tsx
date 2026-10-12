import { createContext, useCallback, useContext, useEffect, useMemo, useReducer, useRef, useState, type ReactNode } from 'react'
import { toast } from 'sonner'
import { applyTheme, type ResolvedMode } from '@/theme/engine'
import { parsePreferences, type Preferences } from './preferences'
import { prefsReducer, type PrefsAction } from './prefsReducer'
import { updateProfile } from './profile'

type SaveState = 'saved' | 'saving' | 'error'

interface PreferencesContext {
  prefs: Preferences
  dispatch: (action: PrefsAction) => void
  mode: ResolvedMode
  saveState: SaveState
}

const Context = createContext<PreferencesContext | null>(null)
const SAVE_DELAY_MS = 700

/**
 * Holds the signed-in user's customisation. Every change applies instantly (theme on <html>,
 * layout in React) and is saved to their profile shortly after, so the next login, on any device,
 * looks exactly the same.
 */
export function PreferencesProvider({ initial, children }: { initial: unknown; children: ReactNode }) {
  const [prefs, dispatch] = useReducer(prefsReducer, initial, parsePreferences)
  const [mode, setMode] = useState<ResolvedMode>(() => applyTheme(prefs.theme))
  const [saveState, setSaveState] = useState<SaveState>('saved')
  const saved = useRef(prefs)
  const latest = useRef(prefs)
  latest.current = prefs

  // Theme: re-apply on change, and follow the OS while in "system" mode.
  useEffect(() => {
    setMode(applyTheme(prefs.theme))
    if (prefs.theme.mode !== 'system') return
    const media = matchMedia('(prefers-color-scheme: dark)')
    const onChange = () => setMode(applyTheme(latest.current.theme))
    media.addEventListener('change', onChange)
    return () => media.removeEventListener('change', onChange)
  }, [prefs.theme])

  // Save, debounced. A failed save keeps the local state and retries on the next change.
  useEffect(() => {
    if (prefs === saved.current) return // nothing changed since the last save (or since load)
    setSaveState('saving')
    const timer = setTimeout(() => {
      updateProfile({ preferences: prefs })
        .then(() => {
          saved.current = prefs
          // A newer change may have arrived while this request was in flight; it is still pending.
          if (latest.current === prefs) setSaveState('saved')
        })
        .catch(() => {
          setSaveState('error')
          toast.error("Couldn't save your changes", { description: 'They are kept on this device and will be saved with your next change.' })
        })
    }, SAVE_DELAY_MS)
    return () => clearTimeout(timer)
  }, [prefs])

  // Closing the tab inside the debounce window: send what we have.
  useEffect(() => {
    const flush = () => {
      if (saveState !== 'saving') return
      const token = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1]
      void fetch('/api/core/me', {
        method: 'PATCH',
        keepalive: true,
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', ...(token ? { 'X-XSRF-TOKEN': decodeURIComponent(token) } : {}) },
        body: JSON.stringify({ preferences: latest.current }),
      })
    }
    window.addEventListener('pagehide', flush)
    return () => window.removeEventListener('pagehide', flush)
  }, [saveState])

  const stableDispatch = useCallback((action: PrefsAction) => dispatch(action), [])
  const value = useMemo(() => ({ prefs, dispatch: stableDispatch, mode, saveState }), [prefs, stableDispatch, mode, saveState])
  return <Context.Provider value={value}>{children}</Context.Provider>
}

export function usePreferences(): PreferencesContext {
  const value = useContext(Context)
  if (!value) throw new Error('usePreferences must be used inside PreferencesProvider')
  return value
}

/** Dispatch a change and offer "Undo" in a toast instead of asking "are you sure?" first. */
export function useUndoable() {
  const { prefs, dispatch } = usePreferences()
  return useCallback((action: PrefsAction, message: string) => {
    const before = prefs
    dispatch(action)
    toast(message, { action: { label: 'Undo', onClick: () => dispatch({ type: 'restore', prefs: before }) } })
  }, [prefs, dispatch])
}
