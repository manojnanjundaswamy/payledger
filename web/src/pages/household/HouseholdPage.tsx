import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams, useSearch } from '@tanstack/react-router'
import { useQueryClient } from '@tanstack/react-query'
import { ArrowRight, Crown, Home, Lock, LogOut, Mail, Pencil, Scale, Trash2, UserPlus, Users } from 'lucide-react'
import { motion } from 'motion/react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Badge, Card, Skeleton } from '@/components/ui/card'
import { Input } from '@/components/ui/controls'
import { CountUp } from '@/components/CountUp'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/cn'
import { formatMoneyWhole } from '@/lib/money'
import { Field } from '@/pages/money/parts'
import {
  ROLES, useCreateHousehold, useHousehold, useHouseholds, useInvite, useInviteInfo, useJoinHousehold, useRemoveMember, useRenameHousehold,
  useRevokeInvite, useSetRole, type Household, type HouseholdRole,
} from '@/state/households'
import { useProfile } from '@/state/profile'
import { chooseSpace } from '@/state/space'

function useMoney() {
  const locale = useProfile().data?.settings.locale ?? 'en-IN'
  return (m: number, currency = 'INR') => formatMoneyWhole(m, currency, locale)
}

/** Switch the money pages to this household and open its dashboard. */
function useOpenMoney() {
  const navigate = useNavigate()
  const client = useQueryClient()
  return (id: string) => {
    chooseSpace(id)
    void client.invalidateQueries({ queryKey: ['money'] })
    void navigate({ to: '/dashboard' })
  }
}

/** Your households, or the case for starting one. */
export function HouseholdsPage() {
  const { data, isPending } = useHouseholds()
  const create = useCreateHousehold()
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [error, setError] = useState<ApiError>()

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(undefined)
    try {
      const h = await create.mutateAsync({ name: name.trim() })
      toast.success(`${h.name} is ready`, { description: 'Invite your family next.' })
      void navigate({ to: '/household/$householdId', params: { householdId: h.id } })
    } catch (err) {
      if (err instanceof ApiError) setError(err)
    }
  }

  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-6">
      {isPending && <Skeleton className="h-40" />}
      {data && data.length > 0 && (
        <section className="grid gap-3 sm:grid-cols-2">
          {data.map((h, i) => (
            <motion.div key={h.id} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: i * 0.05 }}>
              <Link to="/household/$householdId" params={{ householdId: h.id }}
                className="flex items-center gap-4 rounded-card border border-border bg-surface p-pad shadow-card transition-all hover:-translate-y-0.5 hover:shadow-lift">
                <span className="flex size-12 items-center justify-center rounded-full bg-accent-soft text-accent"><Home className="size-6" /></span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-semibold">{h.name}</span>
                  <span className="text-sm text-muted">{h.members} {h.members === 1 ? 'person' : 'people'} · you're {ROLES[h.role].label.toLowerCase()}</span>
                </span>
                <ArrowRight className="size-4 text-subtle" />
              </Link>
            </motion.div>
          ))}
        </section>
      )}

      <Card className="grid gap-6 overflow-hidden p-0 md:grid-cols-[1fr_17rem]">
        <div className="flex flex-col gap-4 p-pad">
          <div>
            <h2 className="text-lg font-semibold">{data?.length ? 'Start another household' : 'One budget for the whole family'}</h2>
            <p className="mt-1 text-sm text-muted">Add your spouse, parents or flatmates. Shared accounts, budgets and bills in one place; everyone's own money stays private.</p>
          </div>
          <ul className="grid gap-2 text-sm sm:grid-cols-3">
            {[{ icon: Users, t: 'Everyone adds', d: 'Each person records what they spend' },
              { icon: Lock, t: 'Private stays private', d: 'Personal accounts are never shared' },
              { icon: Scale, t: 'Who paid what', d: 'Contributions, month by month' }].map((x) => (
              <li key={x.t} className="rounded-control bg-surface-2 p-3"><x.icon className="mb-1 size-4 text-accent" /><span className="block font-medium">{x.t}</span><span className="text-xs text-muted">{x.d}</span></li>
            ))}
          </ul>
          <form onSubmit={submit} className="flex flex-wrap items-end gap-2">
            <Field label="Name" htmlFor="household-name">
              <Input id="household-name" value={name} onChange={(e) => setName(e.target.value)} maxLength={60} placeholder="Sharma home" className="w-64" />
            </Field>
            <Button type="submit" variant="primary" disabled={!name.trim() || create.isPending}><Home />Start the household</Button>
          </form>
          {error && (
            <p className="rounded-control bg-critical/10 px-3 py-2 text-sm text-critical" role="alert">
              {error.message}{error.code === 'plan-limit' && <> · <Link to="/settings" search={{ tab: 'plan' }} className="underline underline-offset-4">See plans</Link></>}
            </p>
          )}
        </div>
        <FamilyDrawing />
      </Card>
    </div>
  )
}

/** A house with four people and a shared ₹ in the middle, drawn with the theme's colours. */
function FamilyDrawing() {
  return (
    <svg viewBox="0 0 220 200" className="hidden h-full w-full bg-accent-soft/60 md:block" role="img" aria-label="A family sharing a budget">
      <path d="M40 95 L110 40 L180 95 V170 H40 Z" fill="var(--surface)" stroke="var(--border-strong)" strokeWidth="2" />
      <path d="M30 100 L110 34 L190 100" fill="none" stroke="var(--accent)" strokeWidth="5" strokeLinecap="round" strokeLinejoin="round" />
      <circle cx="110" cy="112" r="20" fill="var(--accent)" />
      <text x="110" y="120" textAnchor="middle" fontSize="22" fontWeight="700" fill="var(--accent-fg)">₹</text>
      {[[66, 150, 1], [92, 156, 2], [128, 156, 3], [154, 150, 4]].map(([x, y, n]) => (
        <g key={n}>
          <circle cx={x} cy={y! - 16} r="7" fill={`var(--series-${n})`} />
          <rect x={x! - 8} y={y! - 8} width="16" height="18" rx="7" fill={`var(--series-${n})`} />
        </g>
      ))}
    </svg>
  )
}

/** One household: who's in, what they paid this month, invites and roles. */
export function HouseholdDetail() {
  const { householdId } = useParams({ from: '/app/household/$householdId' })
  const { data: h, isPending, isError } = useHousehold(householdId)
  const openMoney = useOpenMoney()
  if (isError) return <Card className="mx-auto max-w-xl p-pad text-sm text-muted">This household doesn't exist, or you're not in it. <Link to="/household" className="underline">Households</Link></Card>
  if (isPending || !h) return <Skeleton className="mx-auto h-64 max-w-4xl" />
  const manages = h.role === 'OWNER' || h.role === 'PARTNER'
  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-6">
      <header className="flex flex-wrap items-center gap-3">
        <span className="flex size-12 items-center justify-center rounded-full bg-accent text-accent-fg"><Home className="size-6" /></span>
        <div className="min-w-0 flex-1"><Title h={h} /><p className="text-sm text-muted">You're {ROLES[h.role].label.toLowerCase()} · {h.members.length} {h.members.length === 1 ? 'person' : 'people'}</p></div>
        <Button variant="primary" onClick={() => openMoney(h.id)} data-testid="open-household-money">Open its money<ArrowRight /></Button>
      </header>
      <Contributions h={h} />
      <Members h={h} />
      {manages && <Invites h={h} />}
    </div>
  )
}

function Title({ h }: { h: Household }) {
  const rename = useRenameHousehold(h.id)
  const [editing, setEditing] = useState(false)
  const [name, setName] = useState(h.name)
  if (!editing) {
    return (
      <h2 className="flex items-center gap-2 text-xl font-semibold" data-testid="household-name">{h.name}
        {h.role === 'OWNER' && <button type="button" aria-label="Rename" onClick={() => setEditing(true)} className="text-subtle hover:text-fg"><Pencil className="size-4" /></button>}
      </h2>
    )
  }
  return (
    <form className="flex gap-2" onSubmit={(e) => { e.preventDefault(); rename.mutate(name.trim(), { onSuccess: () => setEditing(false) }) }}>
      <Input aria-label="Household name" value={name} onChange={(e) => setName(e.target.value)} maxLength={60} autoFocus className="w-56" />
      <Button size="sm" type="submit" variant="primary">Save</Button>
    </form>
  )
}

function Contributions({ h }: { h: Household }) {
  const money = useMoney()
  const navigate = useNavigate()
  const client = useQueryClient()
  const month = new Date(`${h.month}-01`).toLocaleDateString('en-IN', { month: 'long' })
  const fair = h.contributions.length ? 1 / h.contributions.length : 0
  return (
    <Card className="flex flex-col gap-4 p-pad" aria-label="Who paid what">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="font-semibold">Who paid what in {month}</h3>
        <span className="text-sm text-muted"><span className="text-lg font-semibold text-fg"><CountUp to={h.spent} format={(v) => money(v, h.currency)} /></span> spent together</span>
      </div>
      {h.spent === 0 ? (
        <p className="text-sm text-muted">Nothing recorded this month yet. <button type="button" className="text-fg underline underline-offset-4" onClick={() => { chooseSpace(h.id); void client.invalidateQueries({ queryKey: ['money'] }); void navigate({ to: '/transactions', search: { add: 'EXPENSE' } }) }}>Add the first expense</button> in the household.</p>
      ) : (
        <ul className="flex flex-col gap-3">
          {h.contributions.map((c, i) => (
            <li key={c.userId} className="grid grid-cols-[8rem_1fr_auto] items-center gap-3 text-sm" data-testid={`contribution-${c.name}`}>
              <span className="truncate">{c.name}</span>
              <span className="relative h-2.5 overflow-hidden rounded-full bg-surface-2">
                <motion.span className="block h-full rounded-full" style={{ background: `var(--series-${(i % 8) + 1})` }}
                  initial={{ width: 0 }} animate={{ width: `${c.share * 100}%` }} transition={{ duration: 0.7, ease: [0.2, 0.8, 0.2, 1] }} />
                {/* An even split, for comparison. */}
                <span aria-hidden className="absolute -top-0.5 h-3.5 w-0.5 rounded bg-fg/40" style={{ left: `${fair * 100}%` }} />
              </span>
              <span className="tabular text-right">{money(c.spent, h.currency)} <span className="text-xs text-subtle">{Math.round(c.share * 100)}%</span></span>
            </li>
          ))}
        </ul>
      )}
      <p className="text-xs text-subtle">By who recorded each expense in the household's accounts. The mark shows an even split.</p>
    </Card>
  )
}

function Members({ h }: { h: Household }) {
  const setRole = useSetRole(h.id)
  const remove = useRemoveMember(h.id)
  const navigate = useNavigate()
  return (
    <Card className="divide-y divide-border" aria-label="Members">
      <h3 className="px-pad py-3 font-semibold">People</h3>
      {h.members.map((m) => (
        <div key={m.userId} className="flex flex-wrap items-center gap-3 px-pad py-3" data-testid={`member-${m.email}`}>
          <span className="flex size-9 items-center justify-center rounded-full bg-surface-3 text-sm font-semibold">{m.name[0]?.toUpperCase()}</span>
          <span className="min-w-0 flex-1">
            <span className="block truncate text-sm font-medium">{m.name}{m.you && <span className="text-subtle"> (you)</span>}</span>
            <span className="block truncate text-xs text-subtle">{m.email}</span>
          </span>
          {h.role === 'OWNER' && !m.you ? (
            <select aria-label={`Role of ${m.name}`} value={m.role} className="h-8 rounded-control border border-border-strong bg-surface px-2 text-xs"
              onChange={(e) => setRole.mutate({ userId: m.userId, role: e.target.value as HouseholdRole }, {
                onSuccess: () => toast.success(e.target.value === 'OWNER' ? `${m.name} owns ${h.name} now` : 'Role changed'),
              })}>
              {(['PARTNER', 'MEMBER', 'VIEWER', 'OWNER'] as HouseholdRole[]).map((r) => <option key={r} value={r}>{r === 'OWNER' ? 'Make owner' : ROLES[r].label}</option>)}
            </select>
          ) : (
            <Badge tone={m.role === 'OWNER' ? 'accent' : 'neutral'}>{m.role === 'OWNER' && <Crown className="size-3" />}{ROLES[m.role].label}</Badge>
          )}
          {m.you && m.role !== 'OWNER' && (
            <Button size="sm" variant="ghost" onClick={() => remove.mutate(m.userId, { onSuccess: () => { chooseSpace(null); toast(`You left ${h.name}`); void navigate({ to: '/household' }) } })}>
              <LogOut />Leave
            </Button>
          )}
          {!m.you && m.role !== 'OWNER' && (h.role === 'OWNER' || h.role === 'PARTNER') && (
            <Button size="sm" variant="ghost" aria-label={`Remove ${m.name}`} onClick={() => remove.mutate(m.userId, { onSuccess: () => toast(`${m.name} removed`) })}><Trash2 /></Button>
          )}
        </div>
      ))}
    </Card>
  )
}

function Invites({ h }: { h: Household }) {
  const invite = useInvite(h.id)
  const revoke = useRevokeInvite(h.id)
  const [email, setEmail] = useState('')
  const [role, setRole] = useState<HouseholdRole>('MEMBER')
  const [error, setError] = useState<string>()
  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(undefined)
    try {
      const before = h.members.length
      const after = await invite.mutateAsync({ email: email.trim(), role })
      toast.success(after.members.length > before ? 'Added: they already have an account' : 'Invite sent', { description: email.trim() })
      setEmail('')
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not invite. Try again.')
    }
  }
  return (
    <Card className="flex flex-col gap-4 p-pad" aria-label="Invite">
      <h3 className="flex items-center gap-2 font-semibold"><UserPlus className="size-4 text-accent" />Invite someone</h3>
      <form onSubmit={submit} className="flex flex-wrap items-end gap-2">
        <Field label="Email" htmlFor="invite-email"><Input id="invite-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="priya@gmail.com" className="w-64" /></Field>
        <Field label="Role" htmlFor="invite-role">
          <select id="invite-role" value={role} onChange={(e) => setRole(e.target.value as HouseholdRole)} className="h-10 rounded-control border border-border-strong bg-surface px-3 text-sm">
            {(['PARTNER', 'MEMBER', 'VIEWER'] as HouseholdRole[]).map((r) => <option key={r} value={r}>{ROLES[r].label}</option>)}
          </select>
        </Field>
        <Button type="submit" variant="primary" disabled={!email.trim() || invite.isPending}><Mail />Invite</Button>
      </form>
      <p className="text-xs text-subtle">{ROLES[role].hint}. Someone with a PayLedger account joins at once; anyone else gets an email with a link for 14 days.</p>
      {error && <p className="rounded-control bg-critical/10 px-3 py-2 text-sm text-critical" role="alert">{error}</p>}
      {h.invites.length > 0 && (
        <ul className="flex flex-col divide-y divide-border rounded-control border border-border" data-testid="invites">
          {h.invites.map((i) => (
            <li key={i.id} className="flex items-center gap-3 px-3 py-2 text-sm">
              <Mail className="size-4 text-subtle" />
              <span className="min-w-0 flex-1 truncate">{i.email}</span>
              <Badge>{ROLES[i.role].label}</Badge>
              <span className="text-xs text-subtle">until {new Date(i.expiresAt).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })}</span>
              <Button size="sm" variant="ghost" onClick={() => revoke.mutate(i.id)}>Cancel</Button>
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}

/** Where an invite email's link lands (after signing in). */
export function JoinHouseholdPage() {
  const { token } = useSearch({ from: '/app/household/join' })
  const { data: info, isPending, isError } = useInviteInfo(token)
  const join = useJoinHousehold()
  const openMoney = useOpenMoney()
  const navigate = useNavigate()
  const email = useProfile().data?.email
  if (!token || isError) return <Card className="mx-auto max-w-md p-pad text-sm text-muted">This invite link isn't valid. Ask for a new one.</Card>
  if (isPending || !info) return <Skeleton className="mx-auto h-40 max-w-md" />
  return (
    <Card className="mx-auto flex max-w-md flex-col items-center gap-4 p-pad text-center">
      <span className="flex size-14 items-center justify-center rounded-full bg-accent text-accent-fg"><Home className="size-7" /></span>
      <div>
        <h2 className="text-lg font-semibold">{info.invitedBy} invited you to {info.household}</h2>
        <p className="mt-1 text-sm text-muted">as {ROLES[info.role].label.toLowerCase()}: {ROLES[info.role].hint.toLowerCase()}. Your own accounts stay private.</p>
      </div>
      {info.alreadyMember ? (
        <Button variant="primary" onClick={() => void navigate({ to: '/household' })}>You're in already<ArrowRight /></Button>
      ) : info.expired ? (
        <p className="text-sm text-critical">This invite has expired or was used. Ask {info.invitedBy} for a new one.</p>
      ) : !info.forYou ? (
        <p className="text-sm text-muted">It was sent to {info.email}, and you're signed in as {email}. Sign in with that address to join.</p>
      ) : (
        <Button variant="primary" disabled={join.isPending} className={cn(join.isPending && 'opacity-70')}
          onClick={() => join.mutate(token, { onSuccess: (h) => { toast.success(`Welcome to ${h.name}`); openMoney(h.id) }, onError: (e) => toast.error(e.message) })}>
          Join {info.household}
        </Button>
      )}
    </Card>
  )
}
