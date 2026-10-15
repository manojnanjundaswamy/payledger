import { useMemo, useState, type FormEvent } from 'react'
import { AccountPicker } from '@/pages/money/pickers'
import { Link } from '@tanstack/react-router'
import { CalendarClock, ChevronDown, HandCoins, IndianRupee, MessageCircle, Search, UserRoundPlus } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card, Skeleton } from '@/components/ui/card'
import { Input, Segmented } from '@/components/ui/controls'
import { Dialog } from '@/components/ui/overlay'
import { SearchSelect } from '@/components/ui/search-select'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/cn'
import { formatMoney, formatMoneyWhole, parseMoney } from '@/lib/money'
import { upiPayLink } from '@/lib/upi'
import { uuid7 } from '@/lib/uuid'
import { whatsAppShare } from '@/lib/whatsapp'
import { Field, accountOptions, dayLabel, today } from '@/pages/money/parts'
import { useAccounts, useGroupBudget, usePersonalSpaceId } from '@/state/money'
import { useLend, usePeople, type Person } from '@/state/people'
import { useProfile } from '@/state/profile'

type Show = 'all' | 'owed' | 'owe' | 'settled'

/**
 * Everyone you share money with: what each of you owes the other across every group, loans with their due dates,
 * and a way to remind, pay or lend. A loan is kept in a one-to-one ledger with that person (a group of two).
 */
export function PeoplePage() {
  const { data, isPending, isError } = usePeople()
  const [q, setQ] = useState('')
  const [show, setShow] = useState<Show>('all')
  const [lending, setLending] = useState<{ open: boolean; person?: Person; direction?: 'LENT' | 'BORROWED' }>({ open: false })
  const [open, setOpen] = useState<string | null>(null)
  const profile = useProfile().data
  const locale = profile?.settings.locale ?? 'en-IN'
  const money = (m: number) => formatMoneyWhole(m, 'INR', locale)

  const needle = q.trim().toLowerCase()
  const shown = (data?.people ?? []).filter((p) => (!needle || p.name.toLowerCase().includes(needle) || (p.email ?? '').toLowerCase().includes(needle))
    && (show === 'all' || (show === 'owed' ? p.net > 0 : show === 'owe' ? p.net < 0 : p.net === 0)))

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <p className="max-w-md text-sm text-muted">Everyone you share money with, across all your groups. Lend or borrow one to one, with a date to pay back.</p>
        <div className="flex gap-2">
          <Button onClick={() => setLending({ open: true, direction: 'BORROWED' })}>I borrowed</Button>
          <Button variant="primary" onClick={() => setLending({ open: true, direction: 'LENT' })}><HandCoins />I lent money</Button>
        </div>
      </div>

      {isError && <Card className="p-pad text-sm text-critical">Couldn't load your people. Refresh to try again.</Card>}
      {isPending && <Skeleton className="h-32" />}
      {data && (
        <div className="grid gap-3 sm:grid-cols-3">
          <Card className="p-pad"><div className="text-xs text-muted">Owed to you</div><div className="mt-1 text-2xl font-semibold tabular text-good-text">{money(data.totals.owedToYou)}</div></Card>
          <Card className="p-pad"><div className="text-xs text-muted">You owe</div><div className="mt-1 text-2xl font-semibold tabular">{money(data.totals.youOwe)}</div></Card>
          <Card className="p-pad"><div className="text-xs text-muted">People</div><div className="mt-1 text-2xl font-semibold tabular">{data.people.length}</div></Card>
        </div>
      )}

      {data && data.people.length === 0 && (
        <Card className="flex flex-col items-center gap-3 px-pad py-12 text-center">
          <UserRoundPlus className="size-8 text-subtle" />
          <p className="max-w-sm text-sm text-muted">No one yet. People appear here when you share a group, or when you record money lent or borrowed.</p>
          <div className="flex gap-2"><Link to="/groups" className="text-sm underline underline-offset-4">Your groups</Link></div>
        </Card>
      )}

      {data && data.people.length > 0 && (
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-center gap-2">
            <label className="relative min-w-48 flex-1">
              <Search className="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-subtle" />
              <Input aria-label="Search people" placeholder="Search by name or email" value={q} onChange={(e) => setQ(e.target.value)} className="pl-9" />
            </label>
            <Segmented label="Show" value={show} onChange={setShow}
              options={[{ value: 'all', label: 'All' }, { value: 'owed', label: 'Owe you' }, { value: 'owe', label: 'You owe' }, { value: 'settled', label: 'Settled' }]} />
          </div>
          <Card className="divide-y divide-border">
            {shown.length === 0 && <p className="p-pad text-sm text-muted">No one matches.</p>}
            {shown.map((p) => (
              <PersonRow key={p.key} person={p} open={open === p.key} onToggle={() => setOpen(open === p.key ? null : p.key)}
                money={money} myUpi={profile?.settings.upiId ?? null} myName={profile?.displayName ?? ''}
                onLend={(direction) => setLending({ open: true, person: p, direction })} />
            ))}
          </Card>
        </div>
      )}

      <LoanDialog key={`${lending.person?.key ?? 'new'}-${lending.direction}-${lending.open}`} open={lending.open} person={lending.person}
        direction={lending.direction ?? 'LENT'} people={data?.people ?? []} onOpenChange={(o) => setLending({ open: o })} />
    </div>
  )
}

function PersonRow({ person: p, open, onToggle, money, myUpi, myName, onLend }: {
  person: Person; open: boolean; onToggle: () => void; money: (m: number) => string; myUpi: string | null; myName: string
  onLend: (direction: 'LENT' | 'BORROWED') => void
}) {
  const overdue = p.nextDue !== null && p.nextDue < today()
  const settleIn = p.groups.filter((g) => g.net !== 0).sort((a, b) => Math.abs(b.net) - Math.abs(a.net))[0] ?? p.groups[0]
  return (
    <div data-testid={`person-${p.name}`}>
      <button type="button" onClick={onToggle} aria-expanded={open}
        className="flex w-full items-center gap-3 px-pad py-3 text-left hover:bg-surface-2">
        <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-surface-3 text-sm font-semibold" aria-hidden>{p.name[0]?.toUpperCase()}</span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-sm font-medium">{p.name}{!p.registered && <span className="ml-2 text-xs font-normal text-subtle">not on PayLedger</span>}</span>
          <span className="block truncate text-xs text-subtle">
            {p.groups.filter((g) => !g.direct).map((g) => `${g.emoji ?? ''} ${g.name}`.trim()).join(' · ') || 'One to one'}
            {p.nextDue && <span className={cn('ml-2', overdue ? 'text-critical' : 'text-muted')}><CalendarClock className="mr-0.5 inline size-3" />{overdue ? 'was due' : 'due'} {dayLabel(p.nextDue)}</span>}
          </span>
        </span>
        <span className="text-right">
          <span className={cn('block text-sm font-semibold tabular', p.net > 0 && 'text-good-text', p.net === 0 && 'text-subtle')}>
            {p.net === 0 ? 'Settled' : money(Math.abs(p.net))}
          </span>
          {p.net !== 0 && <span className="block text-xs text-subtle">{p.net > 0 ? 'owes you' : 'you owe'}</span>}
        </span>
        <ChevronDown className={cn('size-4 shrink-0 text-subtle transition-transform', open && 'rotate-180')} />
      </button>
      {open && (
        <div className="flex flex-col gap-3 bg-surface-2/50 px-pad pb-4 pt-1">
          <ul className="flex flex-col gap-1">
            {p.groups.map((g) => (
              <li key={g.groupId}>
                <Link to="/groups/$groupId" params={{ groupId: g.groupId }} search={{ tab: g.direct ? 'activity' : 'overview' }}
                  className="flex items-center justify-between gap-2 rounded-control px-2 py-1.5 text-sm hover:bg-surface-3">
                  <span className="truncate">{g.direct ? '🤝 Loans between you' : `${g.emoji ?? '👥'} ${g.name}`}</span>
                  <span className={cn('tabular', g.net > 0 && 'text-good-text', g.net === 0 && 'text-subtle')}>
                    {g.net === 0 ? 'settled' : `${g.net > 0 ? 'owes you' : 'you owe'} ${formatMoney(Math.abs(g.net), g.currency)}`}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
          <div className="flex flex-wrap gap-2">
            {settleIn && p.net !== 0 && (
              <Link to="/groups/$groupId" params={{ groupId: settleIn.groupId }} search={{ tab: 'overview' }}>
                <Button size="sm" variant="primary"><IndianRupee />Settle up</Button>
              </Link>
            )}
            {p.net < 0 && p.upiId && (
              <a href={upiPayLink(p.upiId, p.name, -p.net, 'PayLedger settle-up')}><Button size="sm">Pay by UPI</Button></a>
            )}
            {p.net > 0 && (
              <a target="_blank" rel="noreferrer"
                href={whatsAppShare(`Hi ${p.name}, a reminder: you owe me ${money(p.net)}${p.nextDue ? ` (due ${dayLabel(p.nextDue)})` : ''}.${myUpi ? ` UPI: ${upiPayLink(myUpi, myName, p.net, 'Settle-up')}` : ''} Thanks!`)}>
                <Button size="sm"><MessageCircle />Remind on WhatsApp</Button>
              </a>
            )}
            <Link to="/transactions" search={{ person: p.name }}><Button size="sm" variant="ghost">Their entries</Button></Link>
            <Button size="sm" variant="ghost" onClick={() => onLend('LENT')}>Lend</Button>
            <Button size="sm" variant="ghost" onClick={() => onLend('BORROWED')}>Borrow</Button>
          </div>
        </div>
      )}
    </div>
  )
}

function LoanDialog({ open, onOpenChange, person, direction: initial, people }: {
  open: boolean; onOpenChange: (o: boolean) => void; person?: Person; direction: 'LENT' | 'BORROWED'; people: Person[]
}) {
  const lend = useLend()
  const [id] = useState(() => uuid7())
  const [direction, setDirection] = useState(initial)
  const [who, setWho] = useState(person?.key ?? '')
  const [email, setEmail] = useState('')
  const [amount, setAmount] = useState('')
  const [date, setDate] = useState(today())
  const [due, setDue] = useState('')
  const [note, setNote] = useState('')
  const [account, setAccount] = useState('')
  const [error, setError] = useState<string>()
  const personalSpace = usePersonalSpaceId()
  const { data: accounts } = useAccounts(false, personalSpace)
  const { data: book } = useGroupBudget()
  const payable = (accounts ?? []).filter((a) => a.kind !== 'OWED' && !a.archived)
  const known = people.some((p) => p.key === who)
  const options = useMemo(() => people.map((p) => ({ value: p.key, label: p.name, keywords: p.email ?? undefined })), [people])

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(undefined)
    const value = parseMoney(amount)
    if (!who.trim()) return setError('Who is it with?')
    if (value === null || value <= 0) return setError('Enter the amount, like 5,000.')
    if (due && due < date) return setError("The date to pay back can't be before the loan.")
    try {
      await lend.mutateAsync({
        id, direction, amount: value, occurredOn: date, dueOn: due || null, note: note.trim() || null, accountId: account || null,
        ...(known ? { personKey: who } : { name: who.trim(), email: email.trim() || null }),
      })
      const name = known ? people.find((p) => p.key === who)!.name : who.trim()
      toast.success(direction === 'LENT' ? `Lent ${formatMoney(value)} to ${name}` : `Borrowed ${formatMoney(value)} from ${name}`,
        { description: due ? `We'll remind you on ${dayLabel(due)}.` : undefined })
      onOpenChange(false)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not save. Try again.')
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={direction === 'LENT' ? 'Money you lent' : 'Money you borrowed'}
      description="Kept between the two of you. It isn't spending: it's owed back.">
      <form onSubmit={submit} className="flex flex-col gap-4 p-pad" noValidate>
        <Segmented label="Lent or borrowed" value={direction} onChange={setDirection}
          options={[{ value: 'LENT', label: 'I lent' }, { value: 'BORROWED', label: 'I borrowed' }]} />
        <Field label={direction === 'LENT' ? 'To' : 'From'} htmlFor="loan-who">
          <SearchSelect id="loan-who" value={who} onChange={setWho} options={options} allowCustom customLabel="New person" placeholder="Name" />
        </Field>
        {!known && who.trim() && (
          <Field label="Their email (optional)" htmlFor="loan-email" hint="They can see it and get reminders; without it, it's just your record.">
            <Input id="loan-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="rahul@example.com" />
          </Field>
        )}
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Amount" htmlFor="loan-amount"><Input id="loan-amount" inputMode="decimal" className="tabular" placeholder="5,000" value={amount} onChange={(e) => setAmount(e.target.value)} /></Field>
          <Field label="Date" htmlFor="loan-date"><Input id="loan-date" type="date" value={date} onChange={(e) => setDate(e.target.value)} /></Field>
        </div>
        <Field label="Pay back by (optional)" htmlFor="loan-due" hint="A reminder on the day, to both of you.">
          <Input id="loan-due" type="date" value={due} min={date} onChange={(e) => setDue(e.target.value)} />
        </Field>
        {book?.enabled && payable.length > 0 && (
          <Field label={direction === 'LENT' ? 'Paid from' : 'Received in'} htmlFor="loan-account">
            <AccountPicker space={personalSpace} id="loan-account" value={account} onChange={setAccount} options={[{ value: '', label: 'My usual account' }, ...accountOptions(payable)]} />
          </Field>
        )}
        <Field label="Note (optional)" htmlFor="loan-note"><Input id="loan-note" maxLength={120} placeholder="For the deposit" value={note} onChange={(e) => setNote(e.target.value)} /></Field>
        {error && <p className="rounded-control bg-critical/10 px-3 py-2 text-sm text-critical" role="alert">{error}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button type="submit" variant="primary" disabled={lend.isPending}>Save</Button>
        </div>
      </form>
    </Dialog>
  )
}
