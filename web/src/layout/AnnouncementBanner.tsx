import { useState } from 'react'
import { AlertTriangle, Info, X } from 'lucide-react'
import { cn } from '@/lib/cn'
import { useAnnouncement } from '@/state/admin'

const DISMISSED = 'dismissed-announcement'

/** The message staff set in the admin console. Dismissing hides that message only; a new one shows again. */
export function AnnouncementBanner() {
  const { data } = useAnnouncement()
  const [dismissed, setDismissed] = useState(() => {
    try { return localStorage.getItem(DISMISSED) } catch { return null }
  })
  if (!data || dismissed === data.message) return null
  const warning = data.tone === 'WARNING'
  const Icon = warning ? AlertTriangle : Info
  return (
    <div role="status" className={cn('flex items-start gap-2 border-b px-4 py-2 text-sm sm:px-6 lg:px-8',
      warning ? 'border-warning/30 bg-warning/10' : 'border-border bg-accent-soft')} data-testid="announcement">
      <Icon className={cn('mt-0.5 size-4 shrink-0', warning ? 'text-warning' : 'text-accent')} />
      <p className="flex-1">{data.message}</p>
      <button type="button" aria-label="Dismiss announcement" className="rounded-control p-0.5 text-muted hover:text-fg"
        onClick={() => {
          try { localStorage.setItem(DISMISSED, data.message) } catch { /* it just shows again next time */ }
          setDismissed(data.message)
        }}><X className="size-4" /></button>
    </div>
  )
}
