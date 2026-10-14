import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams, useSearch } from '@tanstack/react-router'
import { ArrowLeft, BarChart3, CalendarDays, Check, Copy, Link2, List, MessageCircle, RefreshCw, UserMinus, UserPlus, Users } from 'lucide-react'
import { Segmented } from '@/components/ui/controls'
import { whatsAppShare } from '@/lib/whatsapp'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Badge, Card, Skeleton } from '@/components/ui/card'
import { Input } from '@/components/ui/controls'
import { ApiError } from '@/lib/api'
import { Field } from '@/pages/money/parts'
import { joinUrl, useAddMember, useGroup, useGroupLink, useRemoveMember, useResetLink, useUpdateGroup } from '@/state/groups'
import { GroupMoney, type GroupTab } from './GroupMoney'

/** One group: who's in it, adding people, and the link that lets friends join. */
export function GroupPage() {
  const { groupId } = useParams({ from: '/app/groups/$groupId' })
  const search = useSearch({ from: '/app/groups/$groupId' })
  const setTab = (t: GroupTab, extra?: { month?: string }) =>
    void navigate({ to: '/groups/$groupId', params: { groupId }, search: (s) => ({ ...s, tab: t === 'overview' ? undefined : t, ...(extra ?? {}) }), replace: true })
  const { data: group, isPending, isError } = useGroup(groupId)
  const remove = useRemoveMember(groupId)
  const update = useUpdateGroup(groupId)
  const navigate = useNavigate()

  // A group of just you needs people first: open People, and stay there while they're added.
  const solo = group?.members.length === 1
  useEffect(() => {
    if (solo && !search.tab) setTab('people')
  }, [solo]) // eslint-disable-line react-hooks/exhaustive-deps

  if (isError) return <Card className="mx-auto max-w-3xl p-pad text-sm text-muted">This group doesn't exist, or you're not in it. <Link to="/groups" className="underline">Your groups</Link></Card>
  if (isPending || !group) return <div className="mx-auto max-w-3xl"><Skeleton className="h-64" /></div>

  const tab: GroupTab = search.tab ?? 'overview'
  const me = group.members.find((m) => m.you)
  const owner = me?.role === 'OWNER'

  async function removeMember(memberId: string, who: string): Promise<boolean> {
    try {
      await remove.mutateAsync(memberId)
      return true
    } catch (err) {
      toast.error(err instanceof ApiError && err.code === 'member-has-balance'
        ? `${who} still owe${who === 'You' ? '' : 's'} or ${who === 'You' ? 'are' : 'is'} owed money here: settle up first.` : 'Could not do that. Try again.')
      return false
    }
  }

  async function leave() {
    if (!me || !(await removeMember(me.id, 'You'))) return
    toast(`You left ${group!.name}`)
    void navigate({ to: '/groups' })
  }

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6">
      <div className="flex items-center gap-3">
        <Button asChild variant="ghost" size="icon" aria-label="Your groups"><Link to="/groups"><ArrowLeft /></Link></Button>
        <span className="flex size-12 items-center justify-center rounded-full bg-surface-2 text-2xl" aria-hidden>{group.emoji ?? '👥'}</span>
        <div className="min-w-0 flex-1">
          <h2 className="truncate text-xl font-semibold" data-testid="group-name">{group.name}</h2>
          <p className="text-sm text-muted">{group.members.length} {group.members.length === 1 ? 'person' : 'people'}{group.archived && ' · archived'}</p>
        </div>
      </div>

      <Segmented label="Group view" value={tab} onChange={(t) => setTab(t)} className="self-start max-sm:[&_svg]:hidden"
        options={[
          { value: 'overview', label: <><BarChart3 />Overview</> },
          { value: 'activity', label: <><List />Activity</> },
          { value: 'calendar', label: <><CalendarDays />Calendar</> },
          { value: 'people', label: <><Users />People</> },
        ]} />

      <GroupMoney group={group} tab={tab} month={search.month} onTab={setTab} />

      {tab === 'people' && <>
      <section aria-label="People" className="flex flex-col gap-2">
        <h3 className="text-sm font-medium text-muted">People</h3>
        <Card className="divide-y divide-border p-0">
          {group.members.map((m) => (
            <div key={m.id} className="flex items-center gap-3 px-pad py-3" data-testid={`member-${m.name}`}>
              <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-surface-2 text-sm font-medium">{m.name.slice(0, 1).toUpperCase()}</span>
              <span className="min-w-0 flex-1">
                <span className="block truncate font-medium">{m.name}{m.you && ' (you)'}</span>
                <span className="block text-xs text-subtle">
                  {m.role === 'OWNER' ? 'Owner' : m.joined ? 'Joined' : m.email ? `Invited: ${m.email}` : 'Not joined yet'}
                </span>
              </span>
              {!m.joined && <Badge>Invited</Badge>}
              {owner && !m.you && m.role !== 'OWNER' && (
                <Button size="sm" variant="ghost" aria-label={`Remove ${m.name}`} onClick={() => void removeMember(m.id, m.name)}><UserMinus /></Button>
              )}
            </div>
          ))}
        </Card>
      </section>

      {!group.archived && <AddPerson groupId={groupId} />}
      {!group.archived && <InviteLink groupId={groupId} />}


      <div className="flex flex-wrap justify-end gap-2">
        {owner
          ? <Button variant="ghost" onClick={() => update.mutate({ archived: !group.archived })}>{group.archived ? 'Unarchive group' : 'Archive group'}</Button>
          : <Button variant="ghost" onClick={leave}><UserMinus />Leave group</Button>}
      </div>
      </>}
    </div>
  )
}

function AddPerson({ groupId }: { groupId: string }) {
  const add = useAddMember(groupId)
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [error, setError] = useState<string>()

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(undefined)
    if (!name.trim()) return setError('Add a name, like "Rahul".')
    try {
      await add.mutateAsync({ name: name.trim(), email: email.trim() || undefined })
      toast.success(`${name.trim()} added`, { description: email.trim() ? 'They join when they sign in with that email, or open the link.' : undefined })
      setName('')
      setEmail('')
    } catch (err) {
      setError(err instanceof ApiError && err.code === 'member-exists' ? 'Someone with that email is already in the group.'
        : err instanceof ApiError && err.status === 400 ? 'Check the email address.' : 'Could not add them. Try again.')
    }
  }

  return (
    <section aria-label="Add someone" className="flex flex-col gap-2">
      <h3 className="text-sm font-medium text-muted">Add someone</h3>
      <Card className="p-pad">
        <form onSubmit={submit} className="grid gap-3 sm:grid-cols-[1fr_1fr_auto] sm:items-end" noValidate>
          <Field label="Name" htmlFor="member-name"><Input id="member-name" maxLength={60} placeholder="Rahul" value={name} onChange={(e) => setName(e.target.value)} /></Field>
          <Field label="Email (optional)" htmlFor="member-email">
            <Input id="member-email" type="email" maxLength={254} placeholder="rahul@example.com" value={email} onChange={(e) => setEmail(e.target.value)} />
          </Field>
          <Button type="submit" variant="outline" disabled={add.isPending}><UserPlus />Add</Button>
        </form>
        {error && <p className="mt-3 rounded-control bg-critical/10 px-3 py-2 text-sm text-critical" role="alert">{error}</p>}
      </Card>
    </section>
  )
}

function InviteLink({ groupId }: { groupId: string }) {
  const [shown, setShown] = useState(false)
  const { data: link } = useGroupLink(groupId, shown)
  const reset = useResetLink(groupId)
  const [copied, setCopied] = useState(false)
  const url = link ? joinUrl(link.token) : ''

  async function copy() {
    try {
      await navigator.clipboard.writeText(url)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      toast('Copy the link from the box')
    }
  }

  return (
    <section aria-label="Invite link" className="flex flex-col gap-2">
      <h3 className="text-sm font-medium text-muted">Invite link</h3>
      <Card className="flex flex-col gap-3 p-pad">
        <p className="text-sm text-muted">Anyone with the link can join after signing in. Reset it to stop the old one working.</p>
        {!shown ? (
          <Button variant="outline" className="self-start" onClick={() => setShown(true)}><Link2 />Show invite link</Button>
        ) : (
          <div className="flex flex-wrap gap-2">
            <Input readOnly value={url} aria-label="Invite link" className="min-w-0 flex-1 font-mono text-xs" onFocus={(e) => e.target.select()} />
            <Button variant="outline" onClick={copy} disabled={!url}>{copied ? <><Check />Copied</> : <><Copy />Copy</>}</Button>
            {url && (
              <Button asChild variant="outline">
                <a href={whatsAppShare(`Join our group on PayLedger to split expenses: ${url}`)} target="_blank" rel="noopener noreferrer">
                  <MessageCircle />WhatsApp
                </a>
              </Button>
            )}
            <Button variant="ghost" onClick={() => reset.mutate()} disabled={reset.isPending}><RefreshCw />Reset</Button>
          </div>
        )}
      </Card>
    </section>
  )
}
