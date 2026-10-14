import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BellRing, CheckCircle2, Clock, XCircle } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card, Skeleton } from '@/components/ui/card'
import { api, ApiError } from '@/lib/api'
import { useCan } from '@/state/admin'
import { useProfile } from '@/state/profile'
import { ExportButton, dateTime } from './parts'

interface Row { id: string; kind: string; to_email: string; subject: string; created_at: string; sent_at: string | null; attempts: number; last_error: string | null }
interface Emails { counts: { pending: number; failed: number; sent24h: number }; emails: Row[] }

/** Is email going out? What was sent, what's waiting, what failed and why (subjects only, never the content). */
export function AdminEmails() {
  const { data, isPending, isError } = useQuery({
    queryKey: ['admin', 'emails'], queryFn: () => api<Emails>('/api/core/admin/emails'), refetchInterval: 5_000,
  })
  const canRun = useCan('SETTINGS_MANAGE')
  const client = useQueryClient()
  const locale = useProfile().data?.settings.locale ?? 'en-IN'
  const run = useMutation({
    mutationFn: () => api<{ users: number; billEmails: number; budgetEmails: number }>('/api/core/admin/emails/run-reminders', { method: 'POST' }),
    onSuccess: (r) => {
      toast.success('Reminders checked', { description: `${r.users} people: ${r.billEmails} bill emails and ${r.budgetEmails} budget alerts queued.` })
      void client.invalidateQueries({ queryKey: ['admin', 'emails'] })
    },
    onError: (e) => toast.error(e instanceof ApiError ? e.message : 'Could not run them'),
  })

  if (isError) return <Card className="p-pad text-sm text-critical">Couldn't load emails. Refresh to try again.</Card>
  if (isPending || !data) return <Skeleton className="h-64" />
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-3">
        <div className="grid flex-1 grid-cols-3 gap-3">
          <Card className="p-pad"><div className="text-sm text-muted">Sent in 24 hours</div><div className="text-2xl font-semibold tabular" data-testid="emails-sent">{data.counts.sent24h}</div></Card>
          <Card className="p-pad"><div className="text-sm text-muted">Waiting</div><div className="text-2xl font-semibold tabular">{data.counts.pending}</div></Card>
          <Card className="p-pad"><div className="text-sm text-muted">Gave up</div><div className="text-2xl font-semibold tabular">{data.counts.failed}</div></Card>
        </div>
        {canRun && <Button variant="outline" onClick={() => run.mutate()} disabled={run.isPending}><BellRing />Run morning reminders now</Button>}
        <ExportButton kind="emails" />
      </div>
      <p className="text-xs text-subtle">Reminders run every morning at 8:00 IST; running them again never sends the same email twice.</p>
      <Card className="divide-y divide-border p-0" data-testid="admin-emails">
        {data.emails.length === 0 ? <p className="p-pad text-sm text-muted">No emails yet.</p> : data.emails.map((e) => (
          <div key={e.id} className="flex items-center gap-3 px-pad py-2.5 text-sm">
            {e.sent_at ? <CheckCircle2 className="size-4 shrink-0 text-good-text" aria-label="Sent" />
              : e.attempts >= 6 ? <XCircle className="size-4 shrink-0 text-critical" aria-label="Failed" />
              : <Clock className="size-4 shrink-0 text-muted" aria-label="Waiting" />}
            <span className="min-w-0 flex-1">
              <span className="block truncate">{e.subject}</span>
              <span className="block truncate text-xs text-subtle">{e.to_email} · {e.kind.toLowerCase().replace(/_/g, ' ')}{e.last_error && !e.sent_at ? ` \u00b7 ${e.last_error}` : ''}</span>
            </span>
            <span className="shrink-0 text-xs text-subtle tabular">{dateTime(e.created_at, locale)}{e.sent_at && ` \u2192 sent ${dateTime(e.sent_at, locale).split(', ').pop()}`}</span>
          </div>
        ))}
      </Card>
    </div>
  )
}
