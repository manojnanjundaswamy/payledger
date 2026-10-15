import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ChevronDown, ChevronRight } from 'lucide-react'
import { Badge, Card, Skeleton } from '@/components/ui/card'
import { Segmented } from '@/components/ui/controls'
import { api } from '@/lib/api'
import { cn } from '@/lib/cn'
import { useCan } from '@/state/admin'
import { useProfile } from '@/state/profile'
import { ExportButton, dateTime } from './parts'

interface Totals { requests: number; errors: number; inputTokens: number; outputTokens: number; costMicros: number; autoAdded: number; toReview: number; notATransaction: number; counted: number; avgLatencyMs: number | null }
interface ByKey { key: string; requests: number; inputTokens: number; outputTokens: number; costMicros: number }
interface ByUser { userId: string | null; email: string | null; requests: number; inputTokens: number; outputTokens: number; costMicros: number; counted: number; last: string }
interface Summary { provider: string; model: string; totals: Totals; features: ByKey[]; models: ByKey[]; users: ByUser[]; days: { day: string; requests: number; costMicros: number }[] }
interface Request {
  id: string; at: string; userId: string | null; email: string | null; feature: string; channel: string | null; provider: string; model: string
  status: string; error: string | null; inputTokens: number; outputTokens: number; audioSeconds: number | null; costMicros: number
  latencyMs: number | null; counted: boolean; modelConfidence: number | null; confidence: number | null; outcome: string | null
  entries: number | null; capturedItemId: string | null; transactionIds: string | null; inputText: string | null; outputText: string | null
}

/** Dollars, to the cent when it's more than a dollar, else to a hundredth of a cent: one read costs about a tenth of a cent. */
const usd = (micros: number) => micros >= 1_000_000 ? `$${(micros / 1_000_000).toFixed(2)}` : `$${(micros / 1_000_000).toFixed(4)}`
const tokens = (n: number) => n >= 1_000_000 ? `${(n / 1_000_000).toFixed(1)}M` : n >= 1_000 ? `${(n / 1_000).toFixed(1)}k` : String(n)
const OUTCOME: Record<string, string> = { AUTO_ADDED: 'Added on its own', PARTLY_ADDED: 'Partly added', TO_REVIEW: 'Left to check', NOT_A_TRANSACTION: 'Not a transaction', FAILED: 'Failed' }
const FEATURE: Record<string, string> = { QUICK_ADD: 'Quick add', CAPTURE: 'Bank alerts', ASSISTANT: 'Voice mode', TRANSCRIBE: 'Speech to text' }

/** Every AI call: what it cost, for whom, why, and what came of it; each one opens to show what was sent (masked) and answered. */
export function AdminAi() {
  const [range, setRange] = useState<'month' | '7d' | 'today'>('month')
  const locale = useProfile().data?.settings.locale ?? 'en-IN'
  const from = (() => {
    const d = new Date()
    if (range === 'today') d.setHours(0, 0, 0, 0)
    else if (range === '7d') d.setDate(d.getDate() - 7)
    else { d.setDate(1); d.setHours(0, 0, 0, 0) }
    return d.toISOString()
  })()
  const summary = useQuery({ queryKey: ['admin', 'ai', range], queryFn: () => api<Summary>(`/api/core/admin/ai?from=${encodeURIComponent(from)}`) })
  const canSeeRequests = useCan('AUDIT_VIEW')
  if (summary.isError) return <Card className="p-pad text-sm text-critical">Couldn't load AI usage. Refresh to try again.</Card>
  if (summary.isPending || !summary.data) return <Skeleton className="h-64" />
  const s = summary.data
  const t = s.totals
  return (
    <div className="flex flex-col gap-4" data-testid="admin-ai">
      <div className="flex flex-wrap items-center gap-3">
        <Segmented label="Period" value={range} onChange={setRange} options={[{ value: 'today', label: 'Today' }, { value: '7d', label: '7 days' }, { value: 'month', label: 'This month' }]} />
        <span className="text-sm text-muted">Model: <b className="text-fg">{s.provider === 'none' ? 'AI is off (no key set)' : `${s.provider} · ${s.model}`}</b></span>
        {canSeeRequests && <span className="ml-auto"><ExportButton kind="ai" params={{ from }} /></span>}
      </div>
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <Stat label="Cost" value={usd(t.costMicros)} sub={t.requests ? `${usd(Math.round(t.costMicros / t.requests))} a request` : undefined} testId="ai-cost" />
        <Stat label="Requests" value={String(t.requests)} sub={`${t.errors} failed · ${t.counted} counted on Free`} />
        <Stat label="Tokens" value={`${tokens(t.inputTokens)} in · ${tokens(t.outputTokens)} out`} sub={t.avgLatencyMs != null ? `${t.avgLatencyMs} ms on average` : undefined} />
        <Stat label="What came of it" value={`${t.autoAdded} added`} sub={`${t.toReview} to check · ${t.notATransaction} not money`} />
      </div>
      <div className="grid gap-3 lg:grid-cols-2">
        <Card className="p-pad">
          <h3 className="mb-2 text-sm font-medium">By feature</h3>
          <Table rows={s.features.map((f) => [FEATURE[f.key] ?? f.key, String(f.requests), `${tokens(f.inputTokens + f.outputTokens)}`, usd(f.costMicros)])} head={['Feature', 'Requests', 'Tokens', 'Cost']} />
          <h3 className="mt-4 mb-2 text-sm font-medium">By model</h3>
          <Table rows={s.models.map((m) => [m.key, String(m.requests), `${tokens(m.inputTokens + m.outputTokens)}`, usd(m.costMicros)])} head={['Model', 'Requests', 'Tokens', 'Cost']} />
        </Card>
        <Card className="p-pad">
          <h3 className="mb-2 text-sm font-medium">By person (most expensive first)</h3>
          <Table rows={s.users.map((u) => [u.email ?? 'deleted user', String(u.requests), tokens(u.inputTokens + u.outputTokens), usd(u.costMicros), dateTime(u.last, locale)])}
            head={['Person', 'Requests', 'Tokens', 'Cost', 'Last']} />
        </Card>
      </div>
      {s.days.length > 1 && (
        <Card className="p-pad">
          <h3 className="mb-3 text-sm font-medium">Per day</h3>
          <div className="flex h-28 items-end gap-1" role="img" aria-label="Requests per day">
            {s.days.map((d) => {
              const max = Math.max(...s.days.map((x) => x.requests), 1)
              return <div key={d.day} title={`${d.day}: ${d.requests} requests, ${usd(d.costMicros)}`} className="flex-1 rounded-t-sm bg-[var(--series-1)]" style={{ height: `${Math.max(4, (d.requests / max) * 100)}%` }} />
            })}
          </div>
        </Card>
      )}
      {canSeeRequests && <Requests from={from} locale={locale} />}
    </div>
  )
}

function Stat({ label, value, sub, testId }: { label: string; value: string; sub?: string; testId?: string }) {
  return (
    <Card className="p-pad">
      <div className="text-sm text-muted">{label}</div>
      <div className="text-xl font-semibold tabular" data-testid={testId}>{value}</div>
      {sub && <div className="text-xs text-subtle">{sub}</div>}
    </Card>
  )
}

function Table({ head, rows }: { head: string[]; rows: string[][] }) {
  if (!rows.length) return <p className="text-sm text-muted">Nothing yet.</p>
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead><tr className="text-left text-xs text-subtle">{head.map((h) => <th key={h} className="pb-1 font-medium">{h}</th>)}</tr></thead>
        <tbody className="divide-y divide-border">
          {rows.map((r, i) => <tr key={i}>{r.map((c, j) => <td key={j} className={cn('py-1.5 pr-3', j === 0 ? 'max-w-[16rem] truncate' : 'tabular whitespace-nowrap')}>{c}</td>)}</tr>)}
        </tbody>
      </table>
    </div>
  )
}

function Requests({ from, locale }: { from: string; locale: string }) {
  const [feature, setFeature] = useState<'' | 'QUICK_ADD' | 'CAPTURE' | 'ASSISTANT' | 'TRANSCRIBE'>('')
  const [open, setOpen] = useState<string | null>(null)
  const q = useQuery({
    queryKey: ['admin', 'ai-requests', feature],
    queryFn: () => api<Request[]>(`/api/core/admin/ai/requests?limit=100${feature ? `&feature=${feature}` : ''}`),
  })
  return (
    <Card className="p-0">
      <div className="flex flex-wrap items-center gap-3 border-b border-border px-pad py-3">
        <h3 className="text-sm font-medium">Requests</h3>
        <Segmented label="Feature" value={feature} onChange={setFeature}
          options={[{ value: '', label: 'All' }, { value: 'QUICK_ADD', label: 'Quick add' }, { value: 'CAPTURE', label: 'Bank alerts' }, { value: 'ASSISTANT', label: 'Voice mode' }, { value: 'TRANSCRIBE', label: 'Speech' }]} />
        <span className="text-xs text-subtle">Texts are masked before sending, and cleared after the retention period.</span>
      </div>
      {q.isPending ? <Skeleton className="m-pad h-24" /> : !q.data?.length ? <p className="p-pad text-sm text-muted">No AI requests{from ? ' yet' : ''}.</p> : (
        <ul className="divide-y divide-border">
          {q.data.map((r) => (
            <li key={r.id}>
              <button type="button" onClick={() => setOpen(open === r.id ? null : r.id)} aria-expanded={open === r.id}
                className="flex w-full flex-wrap items-center gap-x-3 gap-y-1 px-pad py-2.5 text-left text-sm hover:bg-surface-2">
                {open === r.id ? <ChevronDown className="size-4 text-muted" /> : <ChevronRight className="size-4 text-muted" />}
                <span className="tabular w-40 shrink-0 text-xs text-muted">{dateTime(r.at, locale)}</span>
                <span className="min-w-0 flex-1 truncate">{r.email ?? '—'}</span>
                <Badge>{FEATURE[r.feature] ?? r.feature}</Badge>
                {r.status !== 'OK' ? <Badge tone="critical">{r.status}</Badge> : r.outcome && <Badge tone={r.outcome === 'AUTO_ADDED' ? 'good' : 'neutral'}>{OUTCOME[r.outcome] ?? r.outcome}</Badge>}
                {r.confidence != null && <span className="tabular text-xs text-muted">{Math.round(r.confidence * 100)}%</span>}
                <span className="tabular text-xs text-muted">{tokens(r.inputTokens)}/{tokens(r.outputTokens)}</span>
                <span className="tabular w-16 text-right text-xs">{usd(r.costMicros)}</span>
              </button>
              {open === r.id && (
                <div className="grid gap-3 bg-surface-2/50 px-pad py-3 text-xs lg:grid-cols-2">
                  <dl className="grid grid-cols-[8rem_1fr] gap-x-3 gap-y-1 lg:col-span-2">
                    <dt className="text-subtle">Request id</dt><dd className="font-mono">{r.id}</dd>
                    <dt className="text-subtle">Model</dt><dd>{r.provider} · {r.model}</dd>
                    <dt className="text-subtle">Channel</dt><dd>{r.channel ?? '—'}</dd>
                    <dt className="text-subtle">Took</dt><dd>{r.latencyMs != null ? `${r.latencyMs} ms` : '—'}</dd>
                    <dt className="text-subtle">Sure</dt><dd>{r.modelConfidence != null ? `model ${Math.round(r.modelConfidence * 100)}%` : '—'}{r.confidence != null ? ` · after our checks ${Math.round(r.confidence * 100)}%` : ''}</dd>
                    <dt className="text-subtle">Counted on Free</dt><dd>{r.counted ? 'yes' : 'no'}</dd>
                    {r.audioSeconds != null && <><dt className="text-subtle">Audio</dt><dd>{r.audioSeconds} s</dd></>}
                    {r.transactionIds && <><dt className="text-subtle">Transactions</dt><dd className="font-mono">{r.transactionIds}</dd></>}
                    {r.capturedItemId && <><dt className="text-subtle">Captured item</dt><dd className="font-mono">{r.capturedItemId}</dd></>}
                    {r.error && <><dt className="text-subtle">Error</dt><dd className="text-critical">{r.error}</dd></>}
                  </dl>
                  <Block title="Sent (masked)" text={r.inputText} />
                  <Block title="Answer" text={r.outputText} />
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}

function Block({ title, text }: { title: string; text: string | null }) {
  return (
    <div className="min-w-0">
      <div className="mb-1 font-medium text-subtle">{title}</div>
      <pre className="max-h-64 overflow-auto rounded-control border border-border bg-surface p-2 font-mono text-[0.6875rem] whitespace-pre-wrap">{text ?? 'Cleared after the retention period.'}</pre>
    </div>
  )
}
