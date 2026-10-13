import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from '@tanstack/react-router'
import { Plus, Users } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge, Card, Skeleton } from '@/components/ui/card'
import { Input } from '@/components/ui/controls'
import { Dialog } from '@/components/ui/overlay'
import { Field } from '@/pages/money/parts'
import { useCreateGroup, useGroups } from '@/state/groups'

/** Your groups for splitting: trips, flatmates, a couple. */
export function GroupsPage() {
  const { data: groups, isPending, isError } = useGroups()
  const [creating, setCreating] = useState(false)

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
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
        <Card className="divide-y divide-border p-0">
          {groups.map((g) => (
            <Link key={g.id} to="/groups/$groupId" params={{ groupId: g.id }}
              className="flex items-center gap-3 px-pad py-3 hover:bg-surface-2 first:rounded-t-card last:rounded-b-card">
              <span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-surface-2 text-lg" aria-hidden>{g.emoji ?? '👥'}</span>
              <span className="min-w-0 flex-1">
                <span className="block truncate font-medium">{g.name}</span>
                <span className="block text-xs text-subtle">{g.members} {g.members === 1 ? 'person' : 'people'}</span>
              </span>
              {g.archived && <Badge>Archived</Badge>}
            </Link>
          ))}
        </Card>
      )}
      <NewGroupDialog open={creating} onOpenChange={setCreating} />
    </div>
  )
}

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
