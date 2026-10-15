import { useEffect } from 'react'
import { Link } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { Users } from 'lucide-react'
import { Switch } from '@/components/ui/controls'
import { SearchSelect } from '@/components/ui/search-select'
import { api } from '@/lib/api'
import { cn } from '@/lib/cn'
import { useGroups, type Group } from '@/state/groups'

/** "Shared expense" on the add form: which group, who's in, who paid. Split equally; the group handles the rest. */
export interface Shared { on: boolean; groupId: string; members: string[]; payer: string }

export const NOT_SHARED: Shared = { on: false, groupId: '', members: [], payer: '' }

export function useGroupDetail(groupId: string) {
  return useQuery({ queryKey: ['groups', groupId], queryFn: () => api<Group>(`/api/core/groups/${groupId}`), enabled: !!groupId })
}

export function SharedFields({ value, onChange, amount, money }: {
  value: Shared
  onChange: (v: Shared) => void
  /** Paise, to show each share; null while the amount isn't typed yet. */
  amount: number | null
  money: (m: number) => string
}) {
  const { data: groups = [] } = useGroups()
  const live = groups.filter((g) => !g.archived)
  const { data: group } = useGroupDetail(value.groupId)

  // A group chosen: everyone in, you paid (change either below).
  useEffect(() => {
    if (group && value.members.length === 0) onChange({ ...value, members: group.members.map((m) => m.id), payer: group.you })
  }, [group?.id]) // eslint-disable-line react-hooks/exhaustive-deps

  if (live.length === 0) return null
  const share = amount && value.members.length ? Math.floor(amount / value.members.length) : null
  const youIn = !!group && value.members.includes(group.you)
  return (
    <div className={cn('flex flex-col gap-3 rounded-control border p-3 transition-colors', value.on ? 'border-accent bg-accent-soft/30' : 'border-border')}>
      <label className="flex items-center gap-3 text-sm">
        <Switch checked={value.on} onCheckedChange={(on) => onChange(on ? { ...value, on } : NOT_SHARED)} label="Shared expense" />
        <span><span className="font-medium">Shared expense</span><span className="block text-xs text-muted">Split it with a group: your share counts in your budget, the rest is owed to you.</span></span>
        <Users className="ml-auto size-4 text-subtle" />
      </label>
      {value.on && (
        <div className="flex flex-col gap-3">
          <SearchSelect aria-label="Group" value={value.groupId} placeholder="Which group?"
            onChange={(groupId) => onChange({ ...value, groupId, members: [], payer: '' })}
            options={live.map((g) => ({ value: g.id, label: `${g.emoji ?? '👥'} ${g.name}` }))} />
          {group && (
            <>
              <div className="flex flex-wrap gap-1.5" role="group" aria-label="Split between">
                {group.members.map((m) => {
                  const on = value.members.includes(m.id)
                  return (
                    <button key={m.id} type="button" aria-pressed={on}
                      onClick={() => onChange({ ...value, members: on ? value.members.filter((x) => x !== m.id) : [...value.members, m.id] })}
                      className={cn('rounded-full border px-3 py-1 text-xs font-medium transition-colors',
                        on ? 'border-accent bg-accent text-accent-fg' : 'border-border text-muted hover:text-fg')}>
                      {m.you ? 'You' : m.name}
                    </button>
                  )
                })}
              </div>
              <label className="flex items-center gap-2 text-xs text-muted">
                Paid by
                <select aria-label="Paid by" value={value.payer} onChange={(e) => onChange({ ...value, payer: e.target.value })}
                  className="h-8 rounded-control border border-border-strong bg-surface px-2 text-xs text-fg">
                  {group.members.map((m) => <option key={m.id} value={m.id}>{m.you ? 'You' : m.name}</option>)}
                </select>
              </label>
              <p className="text-xs text-muted" data-testid="shared-summary">
                Split equally between {value.members.length || 'no one'}{share !== null && <>: <b className="text-fg">{money(share)}</b> each</>}.
                {youIn && share !== null && <> Your {money(share)} counts in your budget.</>}
                {' '}<Link to="/groups/$groupId" params={{ groupId: group.id }} className="underline underline-offset-4">Other splits in the group</Link>
              </p>
            </>
          )}
        </div>
      )}
    </div>
  )
}
