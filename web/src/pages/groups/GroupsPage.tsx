import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from '@tanstack/react-router'
import { Plus, Search, Users } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge, Card, Skeleton } from '@/components/ui/card'
import { Input, Segmented } from '@/components/ui/controls'
import { Dialog } from '@/components/ui/overlay'
import { BarList } from '@/dashboard/charts'
import { cn } from '@/lib/cn'
import { formatMoneyWhole } from '@/lib/money'
import { Field, dayLabel } from '@/pages/money/parts'
import { useCreateGroup, useGroups, type GroupSummary } from '@/state/groups'
import { useProfile } from '@/state/profile'

type Show = 'all' | 'owe' | 'owed' | 'settled' | 'archived'

/** Your groups for splitting (trips, flatmates, a couple): where you stand across all of them, and each at a glance. */
export function GroupsPage() {
  const { data: groups, isPending, isError } = useGroups()
  const [creating, setCreating] = useState(false)
  const [q, setQ] = useState('')
  const [show, setShow] = useState<Show>('all')
  const navigate = useNavigate()
  const locale = useProfile().data?.settings.locale ?? 'en-IN'
  const money = (m: number) => formatMoneyWhole(m, 'INR', locale)

  const live = (groups ?? []).filter((g) => !g.archived)
  const owe = live.filter((g) => g.net > 0).reduce((s, g) => s + g.net, 0)
  const owed = live.filter((g) => g.net < 0).reduce((s, g) => s - g.net, 0)
  const spent = live.reduce((s, g) => s + g.spent, 0)
  const needle = q.trim().toLowerCase()
  const shown = (groups ?? []).filter((g) => (!needle || g.name.toLowerCase().includes(needle)) && (
    show === 'archived' ? g.archived : show === 'all' ? !g.archived : !g.archived && (show === 'owe' ? g.net > 0 : show === 'owed' ? g.net < 0 : g.net === 0)))

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <p className="max-w-md text-sm text-muted">Trips, flatmates, a couple: add who's in, split what you spend, settle up. Splitting is free on every plan.</p>
        <Button variant="primary" onClick={() => setCreating(true)}><Plus />New group</Button>
      </div>

      {isError && <Card className="p-pad text-sm text-critical">Couldn't load your groups. Refresh to try again.</Card>}
      {isPending && <Skeleton className="h-32" />}
      {groups && groups.length === 0 && (
        <Card className="flex flex-col items-center gap-3 px-pad py-12 text-center">
          <Users className="size-8 text-muted" />
          <p className="font-medium">No groups yet</p>
          <p className="max-w-sm text-sm text-muted">Start one for a trip or your flat, then share its link: friends join in a tap.</p>
          <Button variant="primary" onClick={() => setCreating(true)}><Plus />New group</Button>
        </Card>
      )}

      {groups && groups.length > 0 && (
        <>
          <section aria-label="Across your groups" className="grid gap-3 sm:grid-cols-3">
            {[
              { key: 'owed' as const, label: "You're owed", value: owed, tone: owed > 0 ? 'text-good-text' : '', sub: `${live.filter((g) => g.net < 0).length} group${live.filter((g) => g.net < 0).length === 1 ? '' : 's'}` },
              { key: 'owe' as const, label: 'You owe', value: owe, tone: owe > 0 ? 'text-critical' : '', sub: `${live.filter((g) => g.net > 0).length} group${live.filter((g) => g.net > 0).length === 1 ? '' : 's'}` },
              { key: 'all' as const, label: 'Spent together', value: spent, tone: '', sub: `${live.length} active group${live.length === 1 ? '' : 's'} · ${plural(live.reduce((s, g) => s + g.expenses, 0), 'expense')}` },
            ].map((k) => (
              <button key={k.key} type="button" onClick={() => setShow(k.key)} aria-label={`${k.label}: ${money(k.value)}, show those groups`}
                className={cn('rounded-card border border-border bg-surface p-pad text-left hover:border-border-strong', show === k.key && k.key !== 'all' && 'ring-2 ring-[var(--accent)]')}>
                <div className="text-xs text-muted">{k.label}</div>
                <div className={cn('text-2xl font-semibold tracking-tight tabular', k.tone)} data-testid={`groups-${k.key}`}>{money(k.value)}</div>
                <div className="text-xs text-subtle">{k.sub}</div>
              </button>
            ))}
          </section>

          <div className="grid gap-6 lg:grid-cols-[1fr_20rem]">
            <section aria-label="Groups" className="flex flex-col gap-3">
              <div className="flex flex-wrap items-center gap-2">
                <label className="relative min-w-0 flex-1 basis-48">
                  <span className="sr-only">Search groups</span>
                  <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-subtle" />
                  <Input type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search groups" className="pl-9" />
                </label>
                <Segmented label="Show" value={show} onChange={setShow}
                  options={[{ value: 'all', label: 'All' }, { value: 'owe', label: 'You owe' }, { value: 'owed', label: 'Owed' }, { value: 'settled', label: 'Settled' }, { value: 'archived', label: 'Archived' }]} />
              </div>
              {shown.length === 0 ? (
                <Card className="p-pad text-sm text-muted">No groups here.</Card>
              ) : (
                <div className="grid gap-3 sm:grid-cols-2">
                  {shown.map((g) => <GroupCard key={g.id} g={g} money={money} locale={locale} />)}
                </div>
              )}
            </section>

            {live.some((g) => g.spent > 0) && (
              <section aria-label="Spending by group" className="flex flex-col gap-3">
                <h2 className="text-sm font-medium text-muted">Spending by group</h2>
                <Card className="p-pad">
                  <BarList format={money} onSelect={(id) => void navigate({ to: '/groups/$groupId', params: { groupId: id } })}
                    items={live.filter((g) => g.spent > 0).sort((a, b) => b.spent - a.spent).slice(0, 8)
                      .map((g, i) => ({ key: g.id, label: `${g.emoji ?? '👥'} ${g.name}`, value: g.spent, slot: (i % 8) + 1 }))} />
                </Card>
              </section>
            )}
          </div>
        </>
      )}
      <NewGroupDialog open={creating} onOpenChange={setCreating} />
    </div>
  )
}

/** One group at a glance: who's in, what was spent, where you stand, and when something last happened. */
function GroupCard({ g, money, locale }: { g: GroupSummary; money: (m: number) => string; locale: string }) {
  return (
    <Link to="/groups/$groupId" params={{ groupId: g.id }} data-testid={`group-card-${g.name}`} aria-label={g.name}
      className="flex flex-col gap-3 rounded-card border border-border bg-surface p-pad hover:border-border-strong hover:bg-surface-2/40">
      <span className="flex items-center gap-3">
        <span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-surface-2 text-lg" aria-hidden>{g.emoji ?? '👥'}</span>
        <span className="min-w-0 flex-1">
          <span className="block truncate font-medium">{g.name}</span>
          <span className="block text-xs text-subtle">
            {g.members} {g.members === 1 ? 'person' : 'people'}{g.lastActivity ? ` · ${dayLabel(g.lastActivity, locale)}` : ' · nothing added yet'}
          </span>
        </span>
        {g.archived && <Badge>Archived</Badge>}
      </span>
      <span className="flex items-end justify-between gap-3 text-sm">
        <span>
          <span className="block text-xs text-muted">Spent</span>
          <span className="tabular font-medium">{money(g.spent)}</span>
          <span className="text-xs text-subtle"> · {g.expenses} expense{g.expenses === 1 ? '' : 's'}</span>
        </span>
        <span className={cn('text-right text-sm font-medium tabular', g.net > 0 && 'text-critical', g.net < 0 && 'text-good-text', g.net === 0 && 'text-muted')} data-testid="group-card-net">
          {g.net === 0 ? 'Settled' : g.net > 0 ? `You owe ${money(g.net)}` : `You're owed ${money(-g.net)}`}
        </span>
      </span>
    </Link>
  )
}

const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`

const EMOJIS = ['🏖️', '🏠', '✈️', '🍕', '💑', '🎉', '🚗', '🏔️']

function NewGroupDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const create = useCreateGroup()
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [emoji, setEmoji] = useState(EMOJIS[0]!)
  const [error, setError] = useState<string>()

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!name.trim()) return setError('Give the group a name, like "Goa trip" or "Flat 302".')
    try {
      const group = await create.mutateAsync({ name: name.trim(), emoji })
      onOpenChange(false)
      setName('')
      void navigate({ to: '/groups/$groupId', params: { groupId: group!.id } })
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not create it. Try again.')
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange} title="New group">
      <form onSubmit={submit} className="flex flex-col gap-4 p-pad" noValidate>
        <Field label="Name" htmlFor="group-name">
          <Input id="group-name" autoFocus maxLength={60} placeholder="Goa trip" value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
        <fieldset>
          <legend className="mb-1.5 text-sm font-medium">Picture</legend>
          <div className="flex flex-wrap gap-1">
            {EMOJIS.map((e) => (
              <label key={e} className="flex size-10 cursor-pointer items-center justify-center rounded-control text-lg hover:bg-surface-2 has-[:checked]:bg-accent-soft has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-ring">
                <input type="radio" name="group-emoji" value={e} checked={emoji === e} onChange={() => setEmoji(e)} className="sr-only" aria-label={e} />
                {e}
              </label>
            ))}
          </div>
        </fieldset>
        {error && <p className="rounded-control bg-critical/10 px-3 py-2 text-sm text-critical" role="alert">{error}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button type="submit" variant="primary" disabled={create.isPending}>Create group</Button>
        </div>
      </form>
    </Dialog>
  )
}
