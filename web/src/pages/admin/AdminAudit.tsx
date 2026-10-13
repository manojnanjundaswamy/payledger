import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Card, Skeleton } from '@/components/ui/card'
import { ACTION_LABEL, useAudit } from '@/state/admin'
import { useProfile } from '@/state/profile'

/** Every admin action, newest first. Nothing here can be edited or deleted. */
export function AdminAudit() {
  const [limit, setLimit] = useState(100)
  const { data, isPending, isError } = useAudit(limit)
  const locale = useProfile().data?.settings.locale ?? 'en-IN'

  if (isError) return <Card className="p-pad text-sm text-critical">Couldn't load the log. Refresh to try again.</Card>
  if (isPending || !data) return <Skeleton className="h-64" />
  if (data.length === 0) return <Card className="p-pad text-sm text-muted">No admin actions yet.</Card>
  return (
    <div className="flex flex-col gap-3">
      <p className="text-xs text-subtle">Every change staff make is recorded here, and can't be edited or deleted.</p>
      <Card className="divide-y divide-border p-0" data-testid="audit-log">
        {data.map((e) => (
          <div key={e.id} className="flex flex-wrap items-baseline gap-x-3 gap-y-0.5 px-pad py-3 text-sm">
            <span className="min-w-0 flex-1 basis-72">
              <span className="font-medium">{e.actorEmail}</span> {ACTION_LABEL[e.action] ?? e.action}
              {e.targetEmail && <> for <span className="font-medium">{e.targetEmail}</span></>}
              {typeof e.details.until === 'string' && ` until ${e.details.until}`}
            </span>
            <span className="text-xs text-subtle tabular">{new Date(e.at).toLocaleString(locale, { dateStyle: 'medium', timeStyle: 'short' })}</span>
          </div>
        ))}
      </Card>
      {data.length === limit && <Button variant="ghost" className="self-center" onClick={() => setLimit(limit + 100)}>Show more</Button>}
    </div>
  )
}
