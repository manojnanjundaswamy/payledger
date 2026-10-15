import { Download } from 'lucide-react'
import { Button } from '@/components/ui/button'

/** The time zone admin times are shown in: the staff member's own (from their profile), set by the admin page. */
let zone: string | undefined
export function setAdminTimeZone(timeZone: string | undefined) {
  zone = timeZone
}
export function adminTimeZone(): string {
  return zone ?? Intl.DateTimeFormat().resolvedOptions().timeZone
}

/** Admin times are exact: date and time to the second (14 Oct 2026, 09:30:12), in the staff member's time zone. */
export function dateTime(iso: string | null | undefined, locale = 'en-IN'): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString(locale, {
    day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false, timeZone: adminTimeZone(),
  })
}

/** Downloads a log as CSV with the filters on screen (the server checks the permission and records the export). */
export function ExportButton({ kind, params }: { kind: 'requests' | 'activity' | 'audit' | 'emails' | 'users' | 'ai'; params?: Record<string, string | undefined> }) {
  const q = new URLSearchParams()
  Object.entries(params ?? {}).forEach(([k, v]) => { if (v) q.set(k, v) })
  return (
    <Button asChild variant="outline" size="sm">
      <a href={`/api/core/admin/export/${kind}.csv${q.size ? `?${q}` : ''}`} download data-testid={`export-${kind}`}><Download />Export CSV</a>
    </Button>
  )
}
