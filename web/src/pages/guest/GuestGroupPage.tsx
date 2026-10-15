import { useMemo, useState, type FormEvent } from 'react'
import { MoneyInput } from '@/components/ui/money-input'
import { Link, useNavigate, useParams } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { ArrowRight, Check, HandCoins, IndianRupee, LogOut, Plus, Receipt, Sparkles, Users } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card, Skeleton } from '@/components/ui/card'
import { Input } from '@/components/ui/controls'
import { Dialog } from '@/components/ui/overlay'
import { Logo } from '@/layout/Logo'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/cn'
import { formatMoney, parseMoney } from '@/lib/money'
import { upiPayLink } from '@/lib/upi'
import { uuid7 } from '@/lib/uuid'
import { dayLabel, today } from '@/pages/money/parts'
import { sessionQuery } from '@/state/session'
import { useComments, type Group, type GroupMember } from '@/state/groups'
import { ExpenseComments } from '@/pages/groups/Comments'
import { useGuestActivity, useGuestAddExpense, useGuestBalances, useGuestGroup, useGuestMe, useGuestSettle, useGuestSignOut } from '@/state/guest'

/** A guest's view of a group: who owes whom, settling up, the expenses, and adding one. No account needed. */
export function GuestGroupPage() {
  const { groupId } = useParams({ from: '/guest/groups/$groupId' })
  const { data: group, isPending, isError, error } = useGuestGroup(groupId)
  const { data: me } = useGuestMe()
  const { data: session } = useQuery(sessionQuery)
  const signOut = useGuestSignOut()
  const navigate = useNavigate()
  const [adding, setAdding] = useState(false)

  return (
    <div className="min-h-dvh bg-bg">
      <header className="sticky top-0 z-20 flex h-14 items-center gap-3 border-b border-border bg-bg/85 px-4 backdrop-blur-xl sm:px-6">
        <Logo withName={false} />
        <span className="min-w-0 truncate text-sm text-muted">{me?.email ? <>Guest: <b className="text-fg">{me.email}</b></> : 'Guest'}</span>
        <Button variant="ghost" size="sm" className="ml-auto" onClick={async () => { await signOut.mutateAsync(); void navigate({ to: '/' }) }}>
          <LogOut />Sign out</Button>
      </header>
      <main className="mx-auto flex w-full max-w-3xl flex-col gap-4 px-4 py-5 sm:px-6">
        {isPending ? <Skeleton className="h-64" /> : isError || !group ? (
          <Card className="flex flex-col items-center gap-2 px-pad py-10 text-center">
            <Users className="size-8 text-muted" />
            <p className="font-medium">{error instanceof ApiError && error.status === 401 ? 'Your guest session has ended' : 'This group isn’t available'}</p>
            <p className="text-sm text-muted">Open the group’s invite link again to continue.</p>
          </Card>
        ) : (
          <>
            <div className="flex flex-wrap items-center gap-3">
              <span className="flex size-12 items-center justify-center rounded-full bg-surface-2 text-2xl" aria-hidden>{group.emoji ?? '👥'}</span>
              <div className="min-w-0 flex-1">
                <h1 className="truncate text-2xl font-semibold tracking-tight" data-testid="guest-group-name">{group.name}</h1>
                <p className="text-sm text-muted">{group.members.length} people</p>
              </div>
              <Button variant="primary" onClick={() => setAdding(true)}><Plus />Add an expense</Button>
            </div>
            <Card className="flex flex-col gap-3 border-accent/40 bg-accent-soft p-4 sm:flex-row sm:items-center">
              <p className="flex min-w-0 flex-1 gap-3 text-sm"><Sparkles className="size-5 shrink-0 text-accent" />
                <span className="min-w-0">Make a free account with <b className="break-all">{me?.email ?? 'this email'}</b>: this group comes with you, and your share goes into your own budget.</span></p>
              <Button variant="outline" size="sm" className="self-start sm:self-auto" asChild><a href={session?.signupUrl ?? '/oauth2/authorization/keycloak?signup'}>Create account<ArrowRight /></a></Button>
            </Card>
            <Balances group={group} />
            <Expenses group={group} />
            {me && me.groups.length > 1 && (
              <Card className="p-4">
                <h2 className="mb-2 text-sm font-semibold">Your other groups</h2>
                <ul className="flex flex-wrap gap-2">
                  {me.groups.filter((g) => g.id !== group.id).map((g) => (
                    <li key={g.id}><Link to="/guest/groups/$groupId" params={{ groupId: g.id }} className="rounded-full border border-border px-3 py-1 text-sm hover:border-accent">{g.emoji ?? '👥'} {g.name}</Link></li>
                  ))}
                </ul>
              </Card>
            )}
            <AddExpense group={group} open={adding} onOpenChange={setAdding} />
          </>
        )}
      </main>
    </div>
  )
}

const nameOf = (group: Group, id: string) => {
  const m = group.members.find((x) => x.id === id)
  return m ? (m.you ? 'You' : m.name) : 'Someone who left'
}

function Balances({ group }: { group: Group }) {
  const { data } = useGuestBalances(group.id)
  const settle = useGuestSettle(group.id)
  const money = (n: number) => formatMoney(n, group.currency)
  if (!data) return <Skeleton className="h-32" />
  if (!data.known) return <Card className="p-4 text-sm text-muted">Balances are on their way; refresh in a moment.</Card>
  const mine = data.balance?.[group.you] ?? 0

  async function record(from: string, to: string, amount: number) {
    try {
      await settle.mutateAsync({ fromMemberId: from, toMemberId: to, amount, occurredOn: today() })
      toast.success(`Recorded: ${nameOf(group, from)} paid ${nameOf(group, to)} ${money(amount)}`)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Could not record it')
    }
  }
  return (
    <Card className="flex flex-col gap-3 p-4" data-testid="guest-balances">
      <div className="flex items-baseline justify-between gap-2">
        <h2 className="font-semibold">Balances</h2>
        <p className={cn('text-sm font-medium', mine > 0 ? 'text-critical' : mine < 0 ? 'text-good-text' : 'text-muted')}>
          {mine > 0 ? `You owe ${money(mine)}` : mine < 0 ? `You're owed ${money(-mine)}` : 'You’re settled up'}</p>
      </div>
      {data.payments.length === 0 ? <p className="flex items-center gap-2 text-sm text-muted"><Check className="size-4 text-good-text" />Everyone is settled up.</p> : (
        <ul className="flex flex-col divide-y divide-border">
          {data.payments.map((p) => {
            const payee = group.members.find((m) => m.id === p.to)
            const involved = p.from === group.you || p.to === group.you
            return (
              <li key={`${p.from}-${p.to}`} className="flex flex-wrap items-center gap-2 py-2 text-sm">
                <span className="min-w-0 flex-1"><b>{nameOf(group, p.from)}</b> pays <b>{nameOf(group, p.to)}</b></span>
                <span className="font-medium tabular">{money(p.amount)}</span>
                {p.from === group.you && group.currency === 'INR' && payee?.upiId && (
                  <Button variant="outline" size="sm" asChild>
                    <a href={upiPayLink(payee.upiId, payee.name, p.amount, group.name)}><IndianRupee />Pay with UPI</a>
                  </Button>
                )}
                {involved && <Button variant="outline" size="sm" disabled={settle.isPending} onClick={() => void record(p.from, p.to, p.amount)}><HandCoins />Mark paid</Button>}
              </li>
            )
          })}
        </ul>
      )}
    </Card>
  )
}

function Expenses({ group }: { group: Group }) {
  const { data } = useGuestActivity(group.id)
  const { data: comments } = useComments(group.id, '/api/guest/groups')
  const [open, setOpen] = useState<string | null>(null)
  const money = (n: number) => formatMoney(n, group.currency)
  if (!data) return <Skeleton className="h-40" />
  const items = [
    ...data.expenses.map((e) => ({ kind: 'expense' as const, on: e.occurredOn, e })),
    ...data.settlements.map((s) => ({ kind: 'settlement' as const, on: s.occurredOn, s })),
  ].sort((a, b) => b.on.localeCompare(a.on))
  return (
    <Card className="flex flex-col gap-2 p-4">
      <h2 className="font-semibold">Expenses and payments</h2>
      {items.length === 0 ? <p className="text-sm text-muted">Nothing yet. Add the first expense.</p> : (
        <ul className="flex flex-col divide-y divide-border" data-testid="guest-expenses">
          {items.map((x) => x.kind === 'expense' ? (
            <li key={x.e.id} className="py-2.5">
              <button type="button" className="flex w-full items-center gap-3 text-left" aria-expanded={open === x.e.id} onClick={() => setOpen(open === x.e.id ? null : x.e.id)}>
              <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-surface-2"><Receipt className="size-4" /></span>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium">{x.e.description}</span>
                <span className="block truncate text-xs text-muted">{dayLabel(x.e.occurredOn)} · paid by {x.e.parts.filter((p) => p.paid > 0).map((p) => nameOf(group, p.memberId)).join(', ')}
                  {(() => { const mine = x.e.parts.find((p) => p.memberId === group.you); return mine && mine.share > 0 ? ` · your share ${money(mine.share)}` : '' })()}</span>
              </span>
              <span className="text-right">
                <span className="block text-sm font-medium tabular">{money(x.e.amount)}</span>
                {(() => { const n = (comments ?? []).filter((c) => c.expenseId === x.e.id).length; return n > 0 && <span className="text-xs text-muted">{n} {n === 1 ? 'comment' : 'comments'}</span> })()}
              </span>
              </button>
              {open === x.e.id && <div className="mt-2 pl-12"><ExpenseComments groupId={group.id} expenseId={x.e.id} base="/api/guest/groups" /></div>}
            </li>
          ) : (
            <li key={x.s.id} className="flex items-center gap-3 py-2.5">
              <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-good/10 text-good-text"><HandCoins className="size-4" /></span>
              <span className="min-w-0 flex-1 text-sm">{nameOf(group, x.s.fromMemberId)} paid {nameOf(group, x.s.toMemberId)}<span className="block text-xs text-muted">{dayLabel(x.s.occurredOn)}</span></span>
              <span className="text-sm font-medium tabular">{money(x.s.amount)}</span>
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}

/** Equal split among the people ticked; who paid is one person (people with an account can do more). */
function AddExpense({ group, open, onOpenChange }: { group: Group; open: boolean; onOpenChange: (o: boolean) => void }) {
  const add = useGuestAddExpense(group.id)
  const [description, setDescription] = useState('')
  const [amount, setAmount] = useState('')
  const [on, setOn] = useState(today())
  const [payer, setPayer] = useState(group.you)
  const [between, setBetween] = useState<string[]>(() => group.members.map((m) => m.id))
  const [error, setError] = useState<string>()
  const paise = parseMoney(amount, group.currency)
  const each = useMemo(() => (paise && between.length ? Math.floor(paise / between.length) : 0), [paise, between.length])

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(undefined)
    if (!description.trim()) return setError('What was it for?')
    if (!paise || paise <= 0) return setError('Enter the amount.')
    if (between.length === 0) return setError('Pick who it’s shared between.')
    try {
      await add.mutateAsync({
        id: uuid7(), description: description.trim(), amount: paise, occurredOn: on, split: 'EQUAL',
        paidBy: [{ memberId: payer, value: paise }], shares: between.map((memberId) => ({ memberId })),
      })
      toast.success('Expense added')
      setDescription(''); setAmount('')
      onOpenChange(false)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not add it. Try again.')
    }
  }
  const chip = (m: GroupMember, on: boolean, toggle: () => void) => (
    <button key={m.id} type="button" role="checkbox" aria-checked={on} onClick={toggle}
      className={cn('rounded-full border px-3 py-1 text-sm', on ? 'border-accent bg-accent-soft font-medium' : 'border-border text-muted')}>{m.you ? 'You' : m.name}</button>
  )
  return (
    <Dialog open={open} onOpenChange={onOpenChange} title="Add an expense" description={`In ${group.name}, split equally.`}>
      <form onSubmit={submit} className="flex flex-col gap-4 p-pad">
        <label className="flex flex-col gap-1 text-sm font-medium">What for
          <Input value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Dinner, taxi, groceries…" aria-label="What for" maxLength={140} />
        </label>
        <div className="grid grid-cols-2 gap-3">
          <label className="flex flex-col gap-1 text-sm font-medium">Amount
            <MoneyInput currency={group.currency} value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="0" aria-label="Amount" />
          </label>
          <label className="flex flex-col gap-1 text-sm font-medium">Date
            <Input type="date" value={on} onChange={(e) => setOn(e.target.value)} aria-label="Date" />
          </label>
        </div>
        <div className="flex flex-col gap-2">
          <span className="text-sm font-medium">Paid by</span>
          <div className="flex flex-wrap gap-2" role="radiogroup" aria-label="Paid by">
            {group.members.map((m) => chip(m, payer === m.id, () => setPayer(m.id)))}
          </div>
        </div>
        <div className="flex flex-col gap-2">
          <span className="text-sm font-medium">Shared between</span>
          <div className="flex flex-wrap gap-2" aria-label="Shared between">
            {group.members.map((m) => chip(m, between.includes(m.id), () => setBetween(between.includes(m.id) ? between.filter((x) => x !== m.id) : [...between, m.id])))}
          </div>
          {each > 0 && <p className="text-xs text-muted">About {formatMoney(each, group.currency)} each.</p>}
        </div>
        {error && <p className="text-sm text-critical" role="alert">{error}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" type="button" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button variant="primary" type="submit" disabled={add.isPending}>{add.isPending ? 'Adding…' : 'Add expense'}</Button>
        </div>
      </form>
    </Dialog>
  )
}
