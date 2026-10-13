import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate, useSearch } from '@tanstack/react-router'
import { Ban, KeyRound, LogOut, MailCheck, Search, ShieldCheck, Sparkles, UserPlus } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Badge, Card, Skeleton } from '@/components/ui/card'
import { Input, Segmented } from '@/components/ui/controls'
import { Dialog } from '@/components/ui/overlay'
import { ApiError } from '@/lib/api'
import { Field } from '@/pages/money/parts'
import {
  ACTION_LABEL, PLAN_LABEL, adminApi, useAdminUser, useAdminUserAction, useAdminUsers, useCan, type PlanKind,
} from '@/state/admin'
import { useProfile } from '@/state/profile'

const FILTERS = [
  { value: 'all', label: 'All' },
  { value: 'PREMIUM', label: 'Premium' },
  { value: 'TRIAL', label: 'Trial' },
  { value: 'GRANTED', label: 'Given' },
  { value: 'FREE', label: 'Free' },
  { value: 'DISABLED', label: 'Disabled' },
] as const
type Filter = (typeof FILTERS)[number]['value']

const PAGE = 50

/** Find people, see their plan and how much they use PayLedger, and help them. */
export function AdminUsers() {
  const [q, setQ] = useState('')
  const [search, setSearch] = useState('')
  const { show } = useSearch({ from: '/app/admin' })
  const navigate = useNavigate({ from: '/admin' })
  const filter: Filter = show ?? 'all'
  const setFilter = (f: Filter) => void navigate({ search: (s) => ({ ...s, show: f === 'all' ? undefined : f }), replace: true })
  const [limit, setLimit] = useState(PAGE)
  const [open, setOpen] = useState<string | null>(null)
  const [adding, setAdding] = useState(false)
  const canCreate = useCan('USERS_MANAGE')
  const locale = useProfile().data?.settings.locale ?? 'en-IN'

  // Search as you type, without a request per key.
  useEffect(() => {
    const t = setTimeout(() => { setSearch(q); setLimit(PAGE) }, 250)
    return () => clearTimeout(t)
  }, [q])

  const { data, isPending, isError } = useAdminUsers({
    q: search,
    plan: filter === 'all' || filter === 'DISABLED' ? undefined : filter,
    status: filter === 'DISABLED' ? 'DISABLED' : undefined,
  }, limit)

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-3">
        <label className="relative min-w-60 flex-1">
          <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-subtle" />
          <Input aria-label="Search users" placeholder="Search by email or name" value={q} onChange={(e) => setQ(e.target.value)} className="pl-9" />
        </label>
        <Segmented label="Show" value={filter} onChange={(f) => { setFilter(f); setLimit(PAGE) }} options={[...FILTERS]} className="overflow-x-auto" />
        {canCreate && <Button variant="primary" onClick={() => setAdding(true)}><UserPlus />Add user</Button>}
      </div>

      {isError ? <Card className="p-pad text-sm text-critical">Couldn't load users. Refresh to try again.</Card>
        : isPending || !data ? <Skeleton className="h-64" />
        : data.users.length === 0 ? <Card className="p-pad text-sm text-muted">No one matches.</Card>
        : (
          <Card className="divide-y divide-border p-0">
            <p className="px-pad py-2 text-xs text-subtle" data-testid="users-total">{data.total.toLocaleString(locale)} {data.total === 1 ? 'user' : 'users'}</p>
            {data.users.map((u) => (
              <button key={u.id} type="button" onClick={() => setOpen(u.id)} aria-label={`Open ${u.email}`}
                className="flex w-full flex-wrap items-center gap-x-4 gap-y-1 px-pad py-3 text-left text-sm hover:bg-surface-2">
                <span className="min-w-0 flex-1 basis-56">
                  <span className="block truncate font-medium">{u.displayName}</span>
                  <span className="block truncate text-xs text-muted">{u.email}</span>
                </span>
                <span className="flex flex-wrap items-center gap-1.5">
                  <PlanBadge plan={u.plan} />
                  {u.staffRole && <Badge tone="accent"><ShieldCheck className="size-3" />{u.staffRole.toLowerCase()}</Badge>}
                  {u.disabled && <Badge tone="critical"><Ban className="size-3" />Disabled</Badge>}
                </span>
                <span className="w-40 text-xs text-subtle">
                  Joined {date(u.createdAt, locale)}<br />{u.lastActiveOn ? `Active ${relativeDay(u.lastActiveOn, locale)}` : 'Not active yet'}
                </span>
              </button>
            ))}
            {data.users.length < data.total && (
              <div className="p-3 text-center"><Button variant="ghost" onClick={() => setLimit(limit + PAGE)}>Show more</Button></div>
            )}
          </Card>
        )}

      <UserSheet id={open} onClose={() => setOpen(null)} />
      <AddUserDialog open={adding} onOpenChange={setAdding} onCreated={(id) => { setAdding(false); setOpen(id) }} />
    </div>
  )
}

function PlanBadge({ plan }: { plan: PlanKind }) {
  const tone = plan === 'FREE' ? 'neutral' : plan === 'PAID' ? 'good' : 'accent'
  return <Badge tone={tone}>{plan !== 'FREE' && <Sparkles className="size-3" />}{PLAN_LABEL[plan]}</Badge>
}

/** One user: details, usage counts, plan, support actions and what staff did before. */
function UserSheet({ id, onClose }: { id: string | null; onClose: () => void }) {
  const { data, isPending } = useAdminUser(id)
  const locale = useProfile().data?.settings.locale ?? 'en-IN'
  const can = { support: useCan('USERS_SUPPORT'), manage: useCan('USERS_MANAGE'), plans: useCan('PLANS_MANAGE') }
  const [until, setUntil] = useState('')
  const [confirmDisable, setConfirmDisable] = useState(false)
  const act = useAdminUserAction(async (run: () => Promise<unknown>) => (await run()) as never)
  useEffect(() => { setUntil(''); setConfirmDisable(false) }, [id])

  async function run(what: () => Promise<unknown>, done: string) {
    try {
      await act.mutateAsync(what)
      toast.success(done)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'That didn’t work. Try again.')
    }
  }

  const u = data?.user
  return (
    <Dialog open={id !== null} onOpenChange={(o) => !o && onClose()} side="right" title={u?.displayName ?? 'User'} description={u?.email}>
      {isPending || !data || !u ? <div className="p-pad"><Skeleton className="h-64" /></div> : (
        <div className="flex flex-col gap-6 p-pad text-sm">
          <div className="flex flex-wrap gap-1.5">
            <PlanBadge plan={u.plan} />
            {u.staffRole && <Badge tone="accent"><ShieldCheck className="size-3" />Staff: {u.staffRole.toLowerCase()}</Badge>}
            {u.disabled && <Badge tone="critical"><Ban className="size-3" />Disabled</Badge>}
          </div>

          <dl className="grid grid-cols-2 gap-x-4 gap-y-2">
            <Fact label="Joined" value={date(u.createdAt, locale)} />
            <Fact label="Last active" value={u.lastActiveOn ? relativeDay(u.lastActiveOn, locale) : 'Not yet'} />
            <Fact label="Active days (30)" value={String(data.usage.activeDays30)} />
            <Fact label="Region" value={`${data.locale} · ${data.currency}`} />
            {u.premiumUntil && <Fact label="Premium until" value={date(u.premiumUntil, locale)} />}
            {data.trialStartedAt && <Fact label="Trial started" value={date(data.trialStartedAt, locale)} />}
          </dl>

          <section className="flex flex-col gap-2">
            <h3 className="font-medium">Usage</h3>
            <p className="text-xs text-subtle">Counts only. Staff can't see amounts, descriptions or notes.</p>
            <dl className="grid grid-cols-3 gap-2" data-testid="user-usage">
              {([['Accounts', data.usage.accounts], ['Transactions', data.usage.transactions], ['Budgets', data.usage.budgets],
                ['Bills', data.usage.bills], ['Imports', data.usage.imports], ['Groups', data.usage.groups]] as const).map(([k, v]) => (
                <div key={k} className="rounded-control bg-surface-2 px-3 py-2">
                  <dt className="text-xs text-muted">{k}</dt><dd className="font-semibold tabular">{v}</dd>
                </div>
              ))}
            </dl>
          </section>

          {can.plans && u.plan !== 'COMPLIMENTARY' && u.plan !== 'PAID' && (
            <section className="flex flex-col gap-2">
              <h3 className="font-medium">Premium</h3>
              <form className="flex flex-wrap items-end gap-2" onSubmit={(e: FormEvent) => {
                e.preventDefault()
                if (until) void run(() => adminApi.grant(u.id, until), `Premium until ${date(until, locale)}`)
              }}>
                <Field label="Give Premium until" htmlFor="grant-until">
                  <Input id="grant-until" type="date" value={until} min={today()} onChange={(e) => setUntil(e.target.value)} />
                </Field>
                <Button type="submit" variant="outline" disabled={!until || act.isPending}><Sparkles />Give Premium</Button>
              </form>
              {(u.plan === 'TRIAL' || u.plan === 'GRANTED') && (
                <Button variant="ghost" className="self-start" disabled={act.isPending}
                  onClick={() => void run(() => adminApi.end(u.id), 'Premium ended')}>End Premium now</Button>
              )}
            </section>
          )}

          {(can.support || can.manage) && (
            <section className="flex flex-col gap-2">
              <h3 className="font-medium">Help</h3>
              <div className="flex flex-wrap gap-2">
                {can.support && <>
                  <Button size="sm" variant="outline" disabled={act.isPending}
                    onClick={() => void run(() => adminApi.email(u.id, 'PASSWORD_RESET'), 'Password reset email sent')}><KeyRound />Send password reset</Button>
                  <Button size="sm" variant="outline" disabled={act.isPending}
                    onClick={() => void run(() => adminApi.email(u.id, 'VERIFY_EMAIL'), 'Verification email sent')}><MailCheck />Resend verification</Button>
                  <Button size="sm" variant="outline" disabled={act.isPending}
                    onClick={() => void run(() => adminApi.signOut(u.id), 'Signed out everywhere')}><LogOut />Sign out everywhere</Button>
                </>}
                {can.manage && (u.disabled
                  ? <Button size="sm" variant="outline" disabled={act.isPending}
                      onClick={() => void run(() => adminApi.setDisabled(u.id, false), 'Account enabled')}>Enable account</Button>
                  : confirmDisable
                    ? <Button size="sm" variant="danger" disabled={act.isPending}
                        onClick={() => void run(() => adminApi.setDisabled(u.id, true), 'Account disabled')}><Ban />Yes, disable</Button>
                    : <Button size="sm" variant="ghost" onClick={() => setConfirmDisable(true)}><Ban />Disable account</Button>)}
              </div>
              {confirmDisable && !u.disabled && <p className="text-xs text-muted">They're signed out and can't sign in until enabled again. Nothing is deleted.</p>}
            </section>
          )}

          <section className="flex flex-col gap-2">
            <h3 className="font-medium">What staff did</h3>
            {data.history.length === 0 ? <p className="text-muted">Nothing yet.</p> : (
              <ul className="flex flex-col gap-2" data-testid="user-history">
                {data.history.map((h) => (
                  <li key={h.id} className="text-xs">
                    <span className="text-fg">{h.actorEmail}</span> {ACTION_LABEL[h.action] ?? h.action}
                    {typeof h.details.until === 'string' && ` until ${date(h.details.until, locale)}`}
                    <span className="block text-subtle">{new Date(h.at).toLocaleString(locale, { dateStyle: 'medium', timeStyle: 'short' })}</span>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      )}
    </Dialog>
  )
}

function AddUserDialog({ open, onOpenChange, onCreated }: { open: boolean; onOpenChange: (o: boolean) => void; onCreated: (id: string) => void }) {
  const [email, setEmail] = useState('')
  const [name, setName] = useState('')
  const [until, setUntil] = useState('')
  const [error, setError] = useState<string>()
  const create = useAdminUserAction(adminApi.create)
  useEffect(() => { if (open) { setEmail(''); setName(''); setUntil(''); setError(undefined) } }, [open])

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(undefined)
    if (!/^\S+@\S+\.\S+$/.test(email.trim())) return setError('Enter their email address.')
    if (!name.trim()) return setError('Enter their name.')
    try {
      const detail = await create.mutateAsync({ email: email.trim(), displayName: name.trim(), premiumUntil: until || undefined })
      toast.success('User added', { description: `${detail!.user.email} gets an email to set their password.` })
      onCreated(detail!.user.id)
    } catch (err) {
      setError(err instanceof ApiError && err.code === 'email-in-use' ? 'Someone already uses this email.'
        : err instanceof ApiError ? err.message : 'Could not add the user. Try again.')
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange} title="Add a user" description="They get an email to verify their address and choose a password. You never see it.">
      <form onSubmit={submit} className="flex flex-col gap-4 p-pad" noValidate>
        <Field label="Email" htmlFor="new-user-email"><Input id="new-user-email" type="email" autoFocus value={email} onChange={(e) => setEmail(e.target.value)} /></Field>
        <Field label="Name" htmlFor="new-user-name"><Input id="new-user-name" maxLength={100} value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <Field label="Premium until (optional)" htmlFor="new-user-until" hint="Leave empty to start on Free.">
          <Input id="new-user-until" type="date" min={today()} value={until} onChange={(e) => setUntil(e.target.value)} />
        </Field>
        {error && <p className="rounded-control bg-critical/10 px-3 py-2 text-sm text-critical" role="alert">{error}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button type="submit" variant="primary" disabled={create.isPending}><UserPlus />Add user</Button>
        </div>
      </form>
    </Dialog>
  )
}

function Fact({ label, value }: { label: string; value: string }) {
  return <div><dt className="text-xs text-muted">{label}</dt><dd>{value}</dd></div>
}

function date(iso: string, locale: string) {
  return new Date(iso.length === 10 ? `${iso}T00:00:00` : iso).toLocaleDateString(locale, { day: 'numeric', month: 'short', year: 'numeric' })
}

function relativeDay(iso: string, locale: string) {
  const days = Math.round((new Date(`${today()}T00:00:00`).getTime() - new Date(`${iso}T00:00:00`).getTime()) / 86_400_000)
  return days <= 0 ? 'today' : days === 1 ? 'yesterday' : days < 30 ? `${days} days ago` : date(iso, locale)
}

function today() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

