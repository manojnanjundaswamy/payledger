import { Fragment, useEffect, useState } from 'react'
import { describeAction } from './describe'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useSearch } from '@tanstack/react-router'
import { ChevronDown, ChevronRight, Copy, Search } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card, Skeleton } from '@/components/ui/card'
import { Input, Segmented } from '@/components/ui/controls'
import { api } from '@/lib/api'
import { cn } from '@/lib/cn'
import { useProfile } from '@/state/profile'
import { ExportButton, dateTime } from './parts'

interface Req {
  id: string; at: string; request_id: string; method: string; path: string; route: string | null; status: number; duration_ms: number
  ip: string | null; user_agent: string | null; error_code: string | null; error_detail: string | null; user_id: string | null; email: string | null
}

const STATUS = [{ value: 'all', label: 'All' }, { value: 'errors', label: 'Errors' }, { value: '4xx', label: '4xx' }, { value: '5xx', label: '5xx' }] as const

/** Every API request: find one by its reference, a user, a route or an error; open it for the whole story; export what's shown. */
export function AdminRequests() {
  const locale = useProfile().data?.settings.locale ?? 'en-IN'
  const { show } = useSearch({ from: '/app/admin' })
  const [status, setStatus] = useState<(typeof STATUS)[number]['value']>(show === 'errors' ? 'errors' : 'all')
  const [q, setQ] = useState('')
  const [search, setSearch] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [limit, setLimit] = useState(100)
  const [open, setOpen] = useState<string | null>(null)
  useEffect(() => { const t = setTimeout(() => setSearch(q.trim()), 300); return () => clearTimeout(t) }, [q])

  // datetime-local is the viewer's local time; the server takes an exact instant.
  const iso = (local: string) => (local ? new Date(local).toISOString() : undefined)
  const params: Record<string, string | undefined> = { status: status === 'all' ? undefined : status, q: search || undefined, from: iso(from), to: iso(to) }
  const qs = new URLSearchParams({ limit: String(limit) })
  Object.entries(params).forEach(([k, v]) => { if (v) qs.set(k, v) })
  const { data, isPending, isError } = useQuery({
    queryKey: ['admin', 'requests', params, limit],
    queryFn: () => api<{ total: number; rows: Req[] }>(`/api/core/admin/requests?${qs}`),
    placeholderData: keepPreviousData,
    refetchInterval: 10_000,
  })

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end gap-3">
        <label className="relative min-w-60 flex-1">
          <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-subtle" />
          <Input aria-label="Search requests" placeholder="Reference, email, route, error code or IP" value={q} onChange={(e) => setQ(e.target.value)} className="pl-9" />
        </label>
        <Segmented label="Status" value={status} onChange={setStatus} options={[...STATUS]} />
        <label className="flex flex-col gap-1 text-xs text-muted">From<Input type="datetime-local" step="1" aria-label="From" value={from} onChange={(e) => setFrom(e.target.value)} className="h-9" /></label>
        <label className="flex flex-col gap-1 text-xs text-muted">To<Input type="datetime-local" step="1" aria-label="To" value={to} onChange={(e) => setTo(e.target.value)} className="h-9" /></label>
        <ExportButton kind="requests" params={params} />
      </div>
      <p className="text-xs text-subtle">Every API request, kept 90 days: who, route, outcome and time taken. Request and response contents are never kept.</p>
      {isError ? <Card className="p-pad text-sm text-critical">Couldn't load requests. Refresh to try again.</Card>
        : isPending || !data ? <Skeleton className="h-64" />
        : (
          <Card className="divide-y divide-border p-0" data-testid="requests">
            <p className="px-pad py-2 text-xs text-subtle" data-testid="requests-total">{data.total.toLocaleString(locale)} requests</p>
            {data.rows.map((r) => {
              const expanded = open === r.id
              const bad = r.status >= 400
              return (
                <div key={r.id}>
                  <button type="button" onClick={() => setOpen(expanded ? null : r.id)} aria-expanded={expanded}
                    className="flex w-full items-center gap-3 px-pad py-2 text-left text-sm hover:bg-surface-2">
                    {expanded ? <ChevronDown className="size-4 shrink-0 text-muted" /> : <ChevronRight className="size-4 shrink-0 text-muted" />}
                    <span className={cn('w-10 shrink-0 font-mono text-xs tabular', r.status >= 500 ? 'text-critical' : bad ? 'text-warning' : 'text-good-text')}>{r.status}</span>
                    <span className="w-14 shrink-0 font-mono text-xs text-muted">{r.method}</span>
                    <span className="min-w-0 flex-1 truncate">
                      <span className="block truncate">{describeAction(`${r.method} ${r.route ?? r.path}`, r.status)}</span>
                      <span className="block truncate font-mono text-[0.6875rem] text-subtle">{r.route ?? r.path}</span>
                    </span>
                    <span className="hidden w-48 shrink-0 truncate text-xs text-muted md:block">{r.email ?? 'not signed in'}</span>
                    <span className="w-16 shrink-0 text-right text-xs text-subtle tabular">{r.duration_ms} ms</span>
                    <span className="w-44 shrink-0 text-right text-xs text-subtle tabular">{dateTime(r.at, locale)}</span>
                  </button>
                  {expanded && <Details r={r} locale={locale} />}
                </div>
              )
            })}
            {data.rows.length < data.total && <div className="p-3 text-center"><Button variant="ghost" onClick={() => setLimit(limit + 100)}>Show more</Button></div>}
          </Card>
        )}
    </div>
  )
}

function Details({ r, locale }: { r: Req; locale: string }) {
  const at = new Date(r.at)
  const rows: [string, string][] = [
    ['Reference', r.request_id], ['When', `${dateTime(r.at, locale)}.${String(at.getUTCMilliseconds()).padStart(3, '0')}`],
    ['Request', `${r.method} ${r.path}`], ['Route', r.route ?? '—'], ['Status', String(r.status)], ['Took', `${r.duration_ms} ms`],
    ['User', r.email ? `${r.email} (${r.user_id})` : 'not signed in'], ['IP', r.ip ?? '—'], ['Browser', r.user_agent ?? '—'],
  ]
  if (r.error_code) rows.push(['Error', r.error_code])
  if (r.error_detail) rows.push(['Error detail', r.error_detail])
  return (
    <div className="bg-surface-2 px-pad py-3" data-testid="request-details">
      <dl className="grid gap-x-4 gap-y-1.5 text-xs sm:grid-cols-[8rem_1fr]">
        {rows.map(([k, v]) => <Fragment key={k}><dt className="text-muted">{k}</dt><dd className="font-mono break-all">{v}</dd></Fragment>)}
      </dl>
      <Button size="sm" variant="ghost" className="mt-2" onClick={() => { void navigator.clipboard?.writeText(r.request_id); toast('Reference copied') }}><Copy />Copy reference</Button>
    </div>
  )
}
