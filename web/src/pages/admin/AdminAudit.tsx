import { Fragment, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Card, Skeleton } from '@/components/ui/card'
import { ACTION_LABEL, useAudit } from '@/state/admin'
import { useProfile } from '@/state/profile'
import { ExportButton, dateTime } from './parts'

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
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-xs text-subtle">Every change staff make is recorded here, and can't be edited or deleted. Click one for its details.</p>
        <ExportButton kind="audit" />
      </div>
      <Card className="divide-y divide-border p-0" data-testid="audit-log">
        {data.map((e) => (
          <details key={e.id} className="group">
            <summary className="flex cursor-pointer list-none flex-wrap items-baseline gap-x-3 gap-y-0.5 px-pad py-3 text-sm hover:bg-surface-2">
              <span className="min-w-0 flex-1 basis-72">
                <span className="font-medium">{e.actorEmail}</span> {ACTION_LABEL[e.action] ?? e.action}
                {e.targetEmail && <> for <span className="font-medium">{e.targetEmail}</span></>}
                {typeof e.details.until === 'string' && ` until ${e.details.until}`}
              </span>
              <span className="text-xs text-subtle tabular">{dateTime(e.at, locale)}</span>
            </summary>
            <dl className="grid gap-x-4 gap-y-1.5 bg-surface-2 px-pad py-3 text-xs sm:grid-cols-[8rem_1fr]">
              {([['When', dateTime(e.at, locale)], ['Action', e.action], ['By', `${e.actorEmail} (${e.actorId})`],
                ['For', e.targetEmail ? `${e.targetEmail} (${e.targetId})` : '\u2014'], ['Details', JSON.stringify(e.details, null, 1)]] as [string, string][]).map(([k, v]) => (
                <Fragment key={k}><dt className="text-muted">{k}</dt><dd className="font-mono break-all whitespace-pre-wrap">{v}</dd></Fragment>
              ))}
            </dl>
          </details>
        ))}
      </Card>
      {data.length === limit && <Button variant="ghost" className="self-center" onClick={() => setLimit(limit + 100)}>Show more</Button>}
    </div>
  )
}
