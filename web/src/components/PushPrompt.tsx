import { useEffect, useState } from 'react'
import { motion } from 'motion/react'
import { Bell, BellRing, CalendarClock, HandCoins, PieChart, X } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/cn'
import { playChime } from '@/lib/chime'
import { enablePush, pushState, type PushState } from '@/lib/push'
import { usePreferences } from '@/state/PreferencesProvider'

export function usePushState() {
  const [state, setState] = useState<PushState | null>(null)
  useEffect(() => { void pushState().then(setState) }, [])
  return [state, setState] as const
}

/** Turns push on for this device, with a word on what it does when the browser says no. */
export async function turnOnPush(set: (s: PushState) => void) {
  const s = await enablePush().catch(() => 'off' as PushState)
  set(s)
  if (s === 'on') {
    playChime()
    toast.success('Notifications are on', { description: 'Bills due, budget alerts and money shared with you will reach this device.' })
  } else if (s === 'blocked') {
    toast('Notifications are blocked for this site', { description: 'Allow them in the browser’s site settings (the lock or info icon by the address), then try again.' })
  } else if (s === 'unsupported') {
    toast('This browser can’t show notifications', { description: 'On iPhone, add PayLedger to your Home Screen first; on Android, Chrome works.' })
  }
}

/**
 * The invitation to turn notifications on: a card with a ringing bell, what you'll hear about, and two buttons. Only
 * when the browser hasn't been asked yet and you haven't said "Not now"; the browser's own prompt comes on "Turn on".
 */
export function PushPrompt({ className }: { className?: string }) {
  const { prefs, dispatch } = usePreferences()
  const [state, setState] = usePushState()
  const [busy, setBusy] = useState(false)
  if (state !== 'off' || prefs.pushPromptHidden || (typeof Notification !== 'undefined' && Notification.permission !== 'default')) return null
  // End-to-end tests switch the onboarding cards off (Onboarding.tsx); this is one of them.
  try { if (localStorage.getItem('payledger.e2e.noTour')) return null } catch { /* storage blocked: show it */ }
  return (
    <motion.div initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }} data-testid="push-prompt"
      className={cn('relative flex flex-col gap-3 overflow-hidden rounded-card border border-accent/40 bg-accent-soft p-4 sm:flex-row sm:items-center', className)}>
      <motion.span className="flex size-11 shrink-0 items-center justify-center rounded-full bg-accent text-accent-fg"
        animate={{ rotate: [0, -14, 12, -8, 5, 0] }} transition={{ duration: 1.2, repeat: Infinity, repeatDelay: 3.5 }}>
        <BellRing className="size-5" />
      </motion.span>
      <div className="min-w-0 flex-1">
        <p className="font-medium">Get a gentle chime when money moves</p>
        <ul className="mt-1 flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted">
          <li className="flex items-center gap-1"><CalendarClock className="size-3.5" />Bills due</li>
          <li className="flex items-center gap-1"><PieChart className="size-3.5" />Budget alerts</li>
          <li className="flex items-center gap-1"><HandCoins className="size-3.5" />Splits and settle-ups</li>
          <li className="flex items-center gap-1"><Bell className="size-3.5" />Even with the app closed</li>
        </ul>
      </div>
      <div className="flex gap-2">
        <Button variant="ghost" size="sm" onClick={() => dispatch({ type: 'notifications', patch: { pushPromptHidden: true } })}>Not now</Button>
        <Button variant="primary" size="sm" disabled={busy} onClick={async () => { setBusy(true); await turnOnPush(setState); setBusy(false) }}>
          <BellRing />Turn on</Button>
      </div>
      <button type="button" aria-label="Hide" className="absolute top-2 right-2 rounded-full p-1 text-subtle hover:text-fg sm:hidden"
        onClick={() => dispatch({ type: 'notifications', patch: { pushPromptHidden: true } })}><X className="size-4" /></button>
    </motion.div>
  )
}
