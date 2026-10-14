import { Fragment, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ChevronDown, ChevronsDownUp, ChevronsUpDown, HandCoins, Link2, Pencil, Plus, Receipt, Trash2, Undo2, UserMinus, UserPlus, Users, type LucideIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, Skeleton } from '@/components/ui/card'
import { Segmented } from '@/components/ui/controls'
import { api } from '@/lib/api'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'

type Action = 'GROUP_CREATED' | 'GROUP_UPDATED' | 'MEMBER_ADDED' | 'MEMBER_JOINED' | 'MEMBER_REMOVED' | 'MEMBER_LEFT' | 'LINK_RESET'
  | 'EXPENSE_ADDED' | 'EXPENSE_EDITED' | 'EXPENSE_DELETED' | 'SETTLEMENT_RECORDED' | 'SETTLEMENT_UNDONE'

/** One entry of a group's history (GET /groups/{id}/history); amounts in details are paise. */
export interface GroupEvent {
  id: string; action: Action; actorName: string | null; you: boolean; at: string
  details: Record<string, unknown> & { changes?: { field: string; before?: unknown; after?: unknown }[] }
}

const LOOK: Record<Action, { icon: LucideIcon; kind: 'expenses' | 'payments' | 'people' | 'group' }> = {
  GROUP_CREATED: { icon: Plus, kind: 'group' }, GROUP_UPDATED: { icon: Pencil, kind: 'group' }, LINK_RESET: { icon: Link2, kind: 'group' },
  MEMBER_ADDED: { icon: UserPlus, kind: 'people' }, MEMBER_JOINED: { icon: Users, kind: 'people' },
  MEMBER_REMOVED: { icon: UserMinus, kind: 'people' }, MEMBER_LEFT: { icon: UserMinus, kind: 'people' },
  EXPENSE_ADDED: { icon: Receipt, kind: 'expenses' }, EXPENSE_EDITED: { icon: Pencil, kind: 'expenses' }, EXPENSE_DELETED: { icon: Trash2, kind: 'expenses' },
  SETTLEMENT_RECORDED: { icon: HandCoins, kind: 'payments' }, SETTLEMENT_UNDONE: { icon: Undo2, kind: 'payments' },
}

const FIELDS: Record<string, string> = {
  description: 'What for', amount: 'Amount', occurredOn: 'Date', category: 'Category', split: 'Split', paidBy: 'Paid by', shares: 'Shares',
  name: 'Name', emoji: 'Picture', archived: 'Archived',
}

const SPLITS: Record<string, string> = { EQUAL: 'equally', EXACT: 'by amounts', PERCENT: 'by %', SHARES: 'by shares' }

/**
 * Everything that happened in a group, close to an audit log: each entry says who did what and when (to the second);
 * opening it shows the details, and for an edit what each field was before and after.
 */
export function GroupHistoryList({ groupId, currency, locale }: { groupId: string; currency: string; locale: string }) {
  const { data, isPending, isError } = useQuery({
    queryKey: ['groups', groupId, 'history'],
    queryFn: () => api<GroupEvent[]>(`/api/core/groups/${groupId}/history`),
  })
  const [open, setOpen] = useState<Set<string>>(new Set())
  const [kind, setKind] = useState<'all' | 'expenses' | 'payments' | 'people' | 'group'>('all')
  const money = (v: unknown) => formatMoney(Number(v ?? 0), currency, locale)
  const shown = (data ?? []).filter((e) => kind === 'all' || LOOK[e.action]?.kind === kind)
  const toggle = (id: string) => setOpen((o) => { const n = new Set(o); if (n.has(id)) n.delete(id); else n.add(id); return n })

  if (isError) return <Card className="p-pad text-sm text-critical">Couldn't load the history. Refresh to try again.</Card>
  if (isPending) return <Skeleton className="h-40" />

  return (
    <section aria-label="History" className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2">
        <Segmented label="Show" value={kind} onChange={setKind} options={[
          { value: 'all', label: 'All' }, { value: 'expenses', label: 'Expenses' }, { value: 'payments', label: 'Payments' },
          { value: 'people', label: 'People' }, { value: 'group', label: 'Group' },
        ]} />
        <span className="flex-1" />
        <Button size="sm" variant="ghost" onClick={() => setOpen(new Set(shown.map((e) => e.id)))}><ChevronsUpDown />Expand all</Button>
        <Button size="sm" variant="ghost" onClick={() => setOpen(new Set())}><ChevronsDownUp />Collapse all</Button>
      </div>
      {shown.length === 0 ? <Card className="p-pad text-sm text-muted">Nothing here yet.</Card> : (
        <Card className="divide-y divide-border p-0" data-testid="group-history">
          {shown.map((e) => {
            const look = LOOK[e.action] ?? { icon: Pencil }
            const expanded = open.has(e.id)
            const at = new Date(e.at)
            return (
              <div key={e.id}>
                <button type="button" onClick={() => toggle(e.id)} aria-expanded={expanded}
                  className="flex w-full items-start gap-3 px-pad py-2.5 text-left hover:bg-surface-2" data-testid="history-entry">
                  <span className="mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-full bg-surface-2 text-muted"><look.icon className="size-3.5" /></span>
                  <span className="min-w-0 flex-1">
                    <span className="block text-sm">{sentence(e, money)}</span>
                    <span className="block text-xs text-subtle tabular">
                      {e.you ? 'You' : e.actorName ?? 'Someone'} · {at.toLocaleString(locale, { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                      {Boolean(e.details.backfilled) && ' · from before the history began'}
                    </span>
                  </span>
                  <ChevronDown className={cn('mt-1 size-4 shrink-0 text-subtle transition-transform', expanded && 'rotate-180')} />
                </button>
                {expanded && <Details event={e} money={money} locale={locale} />}
              </div>
            )
          })}
        </Card>
      )}
    </section>
  )
}

/** "Priya added Dinner, ₹900" and the like; the actor is shown under it. */
function sentence(e: GroupEvent, money: (v: unknown) => string): string {
  const d = e.details as Record<string, unknown>
  switch (e.action) {
    case 'GROUP_CREATED': return `Created the group “${d.name}”`
    case 'GROUP_UPDATED': return (e.details.changes ?? []).map((c) =>
      c.field === 'archived' ? (c.after ? 'Archived the group' : 'Brought the group back') : `Changed the ${FIELDS[c.field]?.toLowerCase() ?? c.field} to “${c.after}”`).join(', ') || 'Changed the group'
    case 'MEMBER_ADDED': return `Added ${d.member}${d.email ? ` (${d.email})` : ''}`
    case 'MEMBER_JOINED': return `${d.member} joined${d.via === 'link' ? ' with the invite link' : d.via === 'email' ? ' (invited by email)' : ''}`
    case 'MEMBER_REMOVED': return `Removed ${d.member}`
    case 'MEMBER_LEFT': return `${d.member} left the group`
    case 'LINK_RESET': return 'Reset the invite link (the old one stopped working)'
    case 'EXPENSE_ADDED': return `Added ${d.description}, ${money(d.amount)}`
    case 'EXPENSE_EDITED': return `Edited ${d.description}${(e.details.changes ?? []).length ? `: ${(e.details.changes ?? []).map((c) => FIELDS[c.field]?.toLowerCase() ?? c.field).join(', ')}` : ''}`
    case 'EXPENSE_DELETED': return `Deleted ${d.description}, ${money(d.amount)}`
    case 'SETTLEMENT_RECORDED': return `Recorded ${d.from} paid ${d.to} ${money(d.amount)}`
    case 'SETTLEMENT_UNDONE': return `Undid ${d.from} paying ${d.to} ${money(d.amount)}`
    default: return e.action
  }
}

function Details({ event: e, money, locale }: { event: GroupEvent; money: (v: unknown) => string; locale: string }) {
  const d = e.details as Record<string, unknown>
  const value = (field: string, v: unknown): string => {
    if (v === null || v === undefined || v === '') return '—'
    if (field === 'amount') return money(v)
    if (field === 'occurredOn') return new Date(`${v}T00:00`).toLocaleDateString(locale, { day: 'numeric', month: 'short', year: 'numeric' })
    if (field === 'split') return SPLITS[String(v)] ?? String(v)
    if (field === 'archived') return v ? 'yes' : 'no'
    if (Array.isArray(v)) return v.map((p: { member?: string; amount?: number }) => `${p.member} ${money(p.amount)}`).join(', ') || '—'
    return String(v)
  }
  const rows: [string, string][] = []
  if (e.action.startsWith('EXPENSE') || e.action.startsWith('SETTLEMENT')) {
    for (const f of ['description', 'amount', 'occurredOn', 'category', 'split', 'paidBy', 'shares', 'from', 'to', 'note']) {
      if (d[f] !== undefined) rows.push([FIELDS[f] ?? f[0]!.toUpperCase() + f.slice(1), value(f, d[f])])
    }
  } else {
    for (const [k, v] of Object.entries(d)) if (k !== 'changes' && k !== 'backfilled') rows.push([FIELDS[k] ?? k[0]!.toUpperCase() + k.slice(1), value(k, v)])
  }
  const changes = e.details.changes ?? []
  return (
    <div className="flex flex-col gap-2 bg-surface-2/50 px-pad py-3 pl-[calc(var(--pad)+2.5rem)] text-sm" data-testid="history-details">
      {changes.length > 0 && (
        <table className="w-full text-xs">
          <thead><tr className="text-left text-subtle"><th className="py-1 font-medium">What</th><th className="py-1 font-medium">Before</th><th className="py-1 font-medium">After</th></tr></thead>
          <tbody>
            {changes.map((c) => (
              <tr key={c.field} className="border-t border-border align-top">
                <td className="py-1 pr-3 text-muted">{FIELDS[c.field] ?? c.field}</td>
                <td className="py-1 pr-3 text-critical line-through decoration-1">{value(c.field, c.before)}</td>
                <td className="py-1 text-good-text">{value(c.field, c.after)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {rows.length > 0 && (
        <dl className="grid grid-cols-[8rem_1fr] gap-x-3 gap-y-1 text-xs">
          {rows.map(([k, v]) => <Fragment key={k}><dt className="text-muted">{k}</dt><dd>{v}</dd></Fragment>)}
        </dl>
      )}
      <p className="text-xs text-subtle">By {e.you ? 'you' : e.actorName ?? 'someone'} · {new Date(e.at).toLocaleString(locale, { dateStyle: 'full', timeStyle: 'medium' })}</p>
    </div>
  )
}
