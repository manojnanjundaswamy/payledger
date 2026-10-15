import { useEffect, useRef } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { playChime } from '@/lib/chime'
import { usePreferences } from '@/state/PreferencesProvider'
import { useUnreadCount } from '@/state/inbox'

/**
 * Makes a new notification felt while the app is open: the chime, and a toast to open it. News comes two ways: a
 * push the service worker hands over (the app is in front), or the bell's count going up (no push on this device).
 */
export function NotificationSignals() {
  const { prefs } = usePreferences()
  const navigate = useNavigate()
  const client = useQueryClient()
  const unread = useUnreadCount().data?.unread
  const before = useRef<number | undefined>(undefined)
  const chime = useRef(prefs.chime)
  chime.current = prefs.chime

  // The bell's count went up since the last look: something new arrived.
  useEffect(() => {
    if (unread === undefined) return
    if (before.current !== undefined && unread > before.current && chime.current) playChime()
    before.current = unread
  }, [unread])

  useEffect(() => {
    if (!('serviceWorker' in navigator)) return
    const onMessage = (event: MessageEvent) => {
      const m = event.data as { type?: string; title?: string; body?: string; link?: string }
      if (m?.type === 'push') {
        if (chime.current) playChime()
        void client.invalidateQueries({ queryKey: ['inbox'] })
        toast(m.title ?? 'PayLedger', {
          description: m.body,
          action: m.link ? { label: 'Open', onClick: () => void navigate({ href: m.link! }) } : undefined,
        })
      } else if (m?.type === 'open' && m.link) {
        void navigate({ href: m.link })
      }
    }
    navigator.serviceWorker.addEventListener('message', onMessage)
    return () => navigator.serviceWorker.removeEventListener('message', onMessage)
  }, [client, navigate])
  return null
}
