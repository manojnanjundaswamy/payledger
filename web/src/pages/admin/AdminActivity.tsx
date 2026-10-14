import { Fragment, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ChevronDown, ChevronRight, KeyRound, PenLine, TriangleAlert, UserPlus } from 'lucide-react'
import { Card, Skeleton } from '@/components/ui/card'
import { Segmented } from '@/components/ui/controls'
import { api } from '@/lib/api'
import { cn } from '@/lib/cn'
import { useProfile } from '@/state/profile'
import { ExportButton, dateTime } from './parts'

export interface ActivityRow {
  id: string; at: string; type: 'SIGN_UP' | 'SIGN_IN' | 'CHANGE'; action: string | null; status: number | null
  target: string; ip: string | null; user_agent: string | null; email?: string
}

const SHOW = [{ value: 'all', label: 'Everything' }, { value: 'SIGN_IN', label: 'Sign-ins' }, { value: 'CHANGE', label: 'Changes' }, { value: 'failed', label: 'Errors' }] as const

/** What people did: sign-ins (when, from which browser and address) and changes (which route, the outcome), never contents. */
export function AdminActivity() {
  const [show, setShow] = useState<(typeof SHOW)[number]['value']>('all')
  const params = new URLSearchParams({ limit: '200' })
  if (show === 'SIGN_IN' || show === 'CHANGE') params.set('type', show)
  if (show === 'failed') params.set('failed', 'true')
  const { data, isPending, isError } = useQuery({
    queryKey: ['admin', 'activity', show],
    queryFn: () => api<ActivityRow[]>(`/api/core/admin/activity?${params}`),
    refetchInterval: 10_000,
  })
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-xs text-subtle">Every sign-in and every change, kept 180 days. Routes and ids only: amounts and descriptions are never recorded.</p>
        <span className="flex flex-wrap items-center gap-2">
          <Segmented label="Show" value={show} onChange={setShow} options={[...SHOW]} />
          <ExportButton kind="activity" params={{ status: show === 'failed' ? 'errors' : undefined, q: show === 'SIGN_IN' || show === 'CHANGE' ? show : undefined }} />
        </span>
      </div>
      {isError ? <Card className="p-pad text-sm text-critical">Couldn't load activity. Refresh to try again.</Card>
        : isPending || !data ? <Skeleton className="h-64" />
        : <ActivityList rows={data} withUser />}
    </div>
  )
}

export function ActivityList({ rows, withUser }: { rows: ActivityRow[]; withUser?: boolean }) {
  const locale = useProfile().data?.settings.locale ?? 'en-IN'
  const [open, setOpen] = useState<string | null>(null)
  if (rows.length === 0) return <Card className="p-pad text-sm text-muted">Nothing recorded yet.</Card>
  return (
    <Card className="divide-y divide-border p-0" data-testid="activity-list">
      {rows.map((r) => {
        const failed = (r.status ?? 0) >= 400
        const Icon = r.type === 'SIGN_IN' ? KeyRound : r.type === 'SIGN_UP' ? UserPlus : failed ? TriangleAlert : PenLine
        const expanded = open === r.id
        return (
          <div key={r.id}>
            <button type="button" onClick={() => setOpen(expanded ? null : r.id)} aria-expanded={expanded}
              className="flex w-full items-start gap-3 px-pad py-2.5 text-left text-sm hover:bg-surface-2">
              {expanded ? <ChevronDown className="mt-0.5 size-4 shrink-0 text-muted" /> : <ChevronRight className="mt-0.5 size-4 shrink-0 text-muted" />}
              <Icon className={cn('mt-0.5 size-4 shrink-0', failed ? 'text-critical' : 'text-muted')} />
              <span className="min-w-0 flex-1">
                <span className="block truncate">
                  {withUser && <span className="font-medium">{r.email} </span>}
                  {r.type === 'SIGN_IN' ? 'signed in' : r.type === 'SIGN_UP' ? 'signed up' : <><code className="text-xs">{r.action}</code> <span className={cn('text-xs', failed ? 'text-critical' : 'text-subtle')}>{r.status}</span></>}
                </span>
                <span className="block truncate text-xs text-subtle">{[r.ip, r.user_agent].filter(Boolean).join(' \u00b7 ')}</span>
              </span>
              <span className="shrink-0 text-xs text-subtle tabular">{dateTime(r.at, locale)}</span>
            </button>
            {expanded && (
              <dl className="grid gap-x-4 gap-y-1.5 bg-surface-2 px-pad py-3 text-xs sm:grid-cols-[8rem_1fr]" data-testid="activity-details">
                {([['When', dateTime(r.at, locale)], ['What', r.type === 'CHANGE' ? `${r.action} \u2192 ${r.status}` : r.type.toLowerCase().replace('_', '-')],
                  ['About', r.target && r.target !== '{}' ? r.target : '\u2014'], ['IP', r.ip ?? '\u2014'], ['Browser', r.user_agent ?? '\u2014'],
                  ...(withUser ? [['User', r.email ?? '\u2014']] : [])] as [string, string][]).map(([k, v]) => (
                  <Fragment key={k}><dt className="text-muted">{k}</dt><dd className="font-mono break-all">{v}</dd></Fragment>
                ))}
              </dl>
            )}
          </div>
        )
      })}
    </Card>
  )
}
