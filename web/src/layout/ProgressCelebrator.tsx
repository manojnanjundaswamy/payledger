import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { Confetti } from '@/components/Celebrate'
import { playChime } from '@/lib/chime'
import { usePreferences } from '@/state/PreferencesProvider'
import { useProgress } from '@/state/progress'

/**
 * Checks progress now and then while the app is open; a badge earned since the last look gets confetti, the chime
 * and a toast. (The server keeps the badge and puts it in the bell either way.)
 */
export function ProgressCelebrator() {
  const { prefs } = usePreferences()
  const { data, refetch } = useProgress(prefs.gamification)
  const [party, setParty] = useState(false)
  useEffect(() => {
    if (!prefs.gamification) return
    const t = setInterval(() => { if (document.visibilityState === 'visible') void refetch() }, 60_000)
    return () => clearInterval(t)
  }, [prefs.gamification, refetch])
  useEffect(() => {
    if (!data?.fresh.length) return
    // End-to-end tests switch celebrations off (they'd cover what tests click); the progress test turns them on.
    try { if (localStorage.getItem('payledger.e2e.noTour')) return } catch { /* storage blocked: celebrate */ }
    setParty(true)
    if (prefs.chime) playChime()
    for (const key of data.fresh) {
      const b = data.badges.find((x) => x.key === key)
      if (b) toast.success(`Badge earned: ${b.title}`, { description: `${b.how} +${b.points} points.` })
    }
  }, [data, prefs.chime])
  return party ? <Confetti onDone={() => setParty(false)} /> : null
}
