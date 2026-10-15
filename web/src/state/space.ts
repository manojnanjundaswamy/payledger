import { useSyncExternalStore } from 'react'

/**
 * Which space the money pages show: your personal one, or a household you're in. Kept per device (a household on the
 * laptop, personal on the phone is fine) and falls back to personal if the household is gone.
 */
const KEY = 'payledger.space'
const listeners = new Set<() => void>()

function read(): string | null {
  try {
    return localStorage.getItem(KEY)
  } catch {
    return null
  }
}

export function chooseSpace(id: string | null) {
  try {
    if (id) localStorage.setItem(KEY, id)
    else localStorage.removeItem(KEY)
  } catch {
    // storage blocked: the choice lasts until the page reloads
  }
  memory = id
  listeners.forEach((l) => l())
}

let memory: string | null = read()

export function useChosenSpace(): string | null {
  return useSyncExternalStore((l) => { listeners.add(l); return () => listeners.delete(l) }, () => memory, () => null)
}
