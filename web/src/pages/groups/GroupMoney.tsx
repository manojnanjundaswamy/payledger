import { useState, type FormEvent } from 'react'
import { ArrowRight, ChevronLeft, ChevronRight, HandCoins, IndianRupee, MessageCircle, Pencil, Plus, Receipt, Search } from 'lucide-react'
import { MonthCalendar, monthBounds } from '@/components/MonthCalendar'
import { GroupInsights } from './GroupInsights'
import { GroupHistoryList } from './GroupHistory'
import { Segmented } from '@/components/ui/controls'
import { whatsAppShare } from '@/lib/whatsapp'
import { upiPayLink } from '@/lib/upi'
import { useProfile } from '@/state/profile'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card, Skeleton } from '@/components/ui/card'
import { Input } from '@/components/ui/controls'
import { Dialog } from '@/components/ui/overlay'
import { cn } from '@/lib/cn'
import { formatMoney, formatMoneyShort, minorToInput, parseMoney } from '@/lib/money'
import { Field, Select, accountOptions, dayLabel, today } from '@/pages/money/parts'
import { useAccounts, useGroupBudget } from '@/state/money'
import { SearchSelect } from '@/components/ui/search-select'
import { useActivity, useBalances, useDeleteSettlement, useSettle, type Group, type GroupActivity, type GroupExpense } from '@/state/groups'
import { ExpenseDialog } from './ExpenseDialog'

export type GroupTab = 'overview' | 'activity' | 'calendar' | 'people'

/**
 * A group's money, by tab: the overview (where you stand, settling up, charts), the activity list with search, and the
 * calendar. Editing and recording payments work the same from each.
 */
export function GroupMoney({ group, tab, month, onTab }: {
  group: Group
  tab: GroupTab
  month?: string
  onTab: (tab: GroupTab, extra?: { month?: string }) => void
}) {
  const { data: activity, isPending } = useActivity(group.id)
  const { data: balances } = useBalances(group.id)
  const locale = useProfile().data?.settings.locale ?? 'en-IN'
  const [editing, setEditing] = useState<GroupExpense | 'new' | null>(null)
  const [newDate, setNewDate] = useState<string>()
  const [settling, setSettling] = useState<{ from: string; to: string; amount: number } | null>(null)
  const [q, setQ] = useState('')
  const [who, setWho] = useState<string>()
  const [activityView, setActivityView] = useState<'list' | 'history'>('list')
  const deleteSettlement = useDeleteSettlement(group.id)
  const name = (id: string) => {
    const m = group.members.find((x) => x.id === id)
    return m ? (m.you ? 'You' : m.name) : 'Someone who left'
  }
  const mine = balances?.balance?.[group.you] ?? 0
  const myUpi = useProfile().data?.settings.upiId
  const upiOf = (id: string) => group.members.find((m) => m.id === id)?.upiId ?? null
  const openExpense = (id: string) => {
    const e = activity?.expenses.find((x) => x.id === id)
    if (e) setEditing(e)
  }
  const addOn = (date?: string) => { setNewDate(date); setEditing('new') }

  type Row = { kind: 'expense'; date: string; expense: GroupExpense } | { kind: 'settlement'; date: string; id: string; from: string; to: string; amount: number; note: string | null }
  const rows: Row[] = [
    ...(activity?.expenses ?? []).map((e) => ({ kind: 'expense' as const, date: e.occurredOn, expense: e })),
    ...(activity?.settlements ?? []).map((s) => ({ kind: 'settlement' as const, date: s.occurredOn, id: s.id, from: s.fromMemberId, to: s.toMemberId, amount: s.amount, note: s.note })),
  ].sort((a, b) => b.date.localeCompare(a.date))
  // Search: words in the description or label, an amount, or a payer's name; "who" keeps one member's.
  const needle = q.trim().toLowerCase()
  const amountQ = /^[\d,.]+$/.test(needle) ? parseMoney(needle) : null
  const shown = rows.filter((r) => {
    if (who && (r.kind === 'expense' ? !r.expense.parts.some((p) => p.memberId === who && (p.paid > 0 || p.share > 0)) : r.from !== who && r.to !== who)) return false
    if (!needle) return true
    if (r.kind === 'expense') {
      return r.expense.description.toLowerCase().includes(needle) || (r.expense.category ?? '').toLowerCase().includes(needle)
        || r.expense.amount === amountQ || r.expense.parts.some((p) => p.paid > 0 && name(p.memberId).toLowerCase().includes(needle))
    }
    return `${name(r.from)} paid ${name(r.to)} ${r.note ?? ''}`.toLowerCase().includes(needle) || r.amount === amountQ
  })

  return (
    <div className="flex flex-col gap-6">
      <Card className="flex flex-wrap items-center justify-between gap-4 p-pad">
        <div>
          <p className="text-sm text-muted">Your balance</p>
          <p className={cn('text-2xl font-semibold tracking-tight tabular', mine < 0 && 'text-good-text', mine > 0 && 'text-critical')} data-testid="my-balance">
            {!balances?.known ? '—' : mine === 0 ? 'All settled' : mine < 0 ? `You're owed ${formatMoney(-mine)}` : `You owe ${formatMoney(mine)}`}
          </p>
          {balances && !balances.known && <p className="text-xs text-subtle">Balances are unavailable right now; they will be back shortly.</p>}
        </div>
        {!group.archived && <Button variant="primary" onClick={() => addOn()}><Plus />Add expense</Button>}
      </Card>

      {tab === 'overview' && balances?.known && balances.payments.length > 0 && (
        <section aria-label="Settle up" className="flex flex-col gap-2">
          <h3 className="text-sm font-medium text-muted">Settle up in {balances.payments.length} payment{balances.payments.length === 1 ? '' : 's'}</h3>
          <Card className="divide-y divide-border p-0">
            {balances.payments.map((p) => (
              <div key={`${p.from}-${p.to}`} className="flex flex-wrap items-center gap-3 px-pad py-3 text-sm" data-testid={`payment-${name(p.from)}-${name(p.to)}`}>
                <span className="font-medium">{name(p.from)}</span><ArrowRight className="size-4 text-muted" /><span className="font-medium">{name(p.to)}</span>
                <span className="flex-1 tabular">{formatMoney(p.amount)}</span>
                {/* You owe them and they have a UPI id: pay in one tap from a phone's UPI app. */}
                {p.from === group.you && upiOf(p.to) && (
                  <Button asChild size="sm" variant="primary">
                    <a href={upiPayLink(upiOf(p.to)!, name(p.to), p.amount, `${group.name} settle-up`)} aria-label={`Pay ${name(p.to)} via UPI`}
                      title={`Opens your UPI app to pay ${upiOf(p.to)}. On a computer, open PayLedger on your phone.`}>
                      <IndianRupee />Pay via UPI
                    </a>
                  </Button>
                )}
                {/* A friendly nudge, written for them; they pay by UPI and the payment is recorded here. */}
                {p.to === group.you && (
                  <Button asChild size="sm" variant="ghost">
                    <a href={whatsAppShare(`Hi ${name(p.from)}, a reminder for ${group.name}: you owe me ${formatMoney(p.amount)}.${myUpi ? ` Pay by UPI to ${myUpi}: ${upiPayLink(myUpi, name(p.to), p.amount, `${group.name} settle-up`)}` : ''} Thanks!`)}
                      target="_blank" rel="noopener noreferrer" aria-label={`Remind ${name(p.from)} on WhatsApp`}><MessageCircle />Remind</a>
                  </Button>
                )}
                {!group.archived && <Button size="sm" variant="outline" onClick={() => setSettling(p)}><HandCoins />Record payment</Button>}
              </div>
            ))}
          </Card>
        </section>
      )}

      {tab === 'overview' && (
        <GroupInsights group={group} locale={locale} onExpense={openExpense}
          onLabel={(label) => { setWho(undefined); setQ(label ?? ''); onTab('activity') }}
          onMember={(id) => { setQ(''); setWho(id); onTab('activity') }}
          onMonth={(m) => onTab('calendar', { month: m })} />
      )}

      {tab === 'calendar' && (
        <GroupCalendar group={group} month={month ?? new Date().toISOString().slice(0, 7)} locale={locale} activity={activity}
          name={name} onMonth={(m) => onTab('calendar', { month: m })} onOpen={openExpense} onAdd={group.archived ? undefined : addOn} />
      )}

      {tab === 'activity' && (
        <Segmented label="Activity view" value={activityView} onChange={setActivityView} className="self-start"
          options={[{ value: 'list', label: 'Expenses & payments' }, { value: 'history', label: 'History' }]} />
      )}
      {tab === 'activity' && activityView === 'history' && <GroupHistoryList groupId={group.id} currency={group.currency} locale={locale} />}

      {tab === 'activity' && activityView === 'list' && (
        <section aria-label="Activity" className="flex flex-col gap-2">
          <div className="flex flex-wrap items-center gap-2">
            <label className="relative min-w-0 flex-1 basis-52">
              <span className="sr-only">Search this group</span>
              <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-subtle" />
              <Input type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search: dinner, Food, 600, a name…" className="pl-9" />
            </label>
            <Select aria-label="Whose" value={who ?? ''} onChange={(e) => setWho(e.target.value || undefined)} className="w-auto min-w-36">
              <option value="">Everyone</option>
              {group.members.map((m) => <option key={m.id} value={m.id}>{m.you ? 'You' : m.name}</option>)}
            </Select>
          </div>
          {(needle || who) && (
            <p className="text-xs text-muted" data-testid="group-found">
              {shown.length} of {rows.length} · {formatMoney(shown.reduce((s, r) => s + (r.kind === 'expense' ? r.expense.amount : 0), 0))} in expenses
            </p>
          )}
          {isPending ? <Skeleton className="h-24" /> : rows.length === 0 ? (
            <Card className="p-pad text-sm text-muted">No expenses yet. Add the first one: who paid, and who it was for.</Card>
          ) : shown.length === 0 ? (
            <Card className="p-pad text-sm text-muted">Nothing matches. <button type="button" className="underline" onClick={() => { setQ(''); setWho(undefined) }}>Show everything</button></Card>
          ) : (
            <Card className="divide-y divide-border p-0">
              {shown.map((r) => r.kind === 'expense' ? (
                <button key={r.expense.id} type="button" onClick={() => setEditing(r.expense)} aria-label={`Edit ${r.expense.description}`}
                  className="flex w-full items-center gap-3 px-pad py-3 text-left hover:bg-surface-2">
                  <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-surface-2 text-muted"><Receipt className="size-4" /></span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-medium">{r.expense.description}</span>
                    <span className="block truncate text-xs text-subtle">
                      {dayLabel(r.date)}{r.expense.loan ? ' · Loan' : r.expense.category && ` · ${r.expense.category}`}{r.expense.loan && r.expense.dueOn && ` · pay back by ${dayLabel(r.expense.dueOn)}`} · {r.expense.parts.filter((p) => p.paid > 0).map((p) => name(p.memberId)).join(' & ')} {r.expense.loan ? 'lent it' : 'paid'}
                    </span>
                  </span>
                  <span className="text-right">
                    <span className="block tabular font-medium">{formatMoney(r.expense.amount, r.expense.currency)}</span>
                    <YourPart expense={r.expense} you={group.you} />
                  </span>
                </button>
              ) : (
                <div key={r.id} className="flex items-center gap-3 px-pad py-3 text-sm">
                  <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-good/10 text-good-text"><HandCoins className="size-4" /></span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-medium">{name(r.from)} paid {name(r.to)}</span>
                    <span className="block text-xs text-subtle">{dayLabel(r.date)}{r.note && ` · ${r.note}`}</span>
                  </span>
                  <span className="tabular">{formatMoney(r.amount)}</span>
                  <Button size="sm" variant="ghost" aria-label="Delete this payment" onClick={() => deleteSettlement.mutate(r.id)}>Undo</Button>
                </div>
              ))}
            </Card>
          )}
        </section>
      )}

      <ExpenseDialog open={editing !== null} onOpenChange={(o) => !o && setEditing(null)} groupId={group.id} initialDate={newDate}
        members={group.members} you={group.you} expense={editing === 'new' || editing === null ? undefined : editing} />
      <SettleDialog payment={settling} onClose={() => setSettling(null)} groupId={group.id} name={name} you={group.you} />
    </div>
  )
}

/** The group's expenses and payments on a month calendar; a day adds an expense on that date. */
function GroupCalendar({ group, month, locale, activity, name, onMonth, onOpen, onAdd }: {
  group: Group; month: string; locale: string; activity?: GroupActivity; name: (id: string) => string
  onMonth: (m: string) => void; onOpen: (id: string) => void; onAdd?: (date: string) => void
}) {
  const { start, end, prev, next } = monthBounds(month)
  const expenses = (activity?.expenses ?? []).filter((e) => e.occurredOn >= start && e.occurredOn <= end)
  const settlements = (activity?.settlements ?? []).filter((s) => s.occurredOn >= start && s.occurredOn <= end)
  const spentOn = (day: string) => expenses.filter((e) => e.occurredOn === day).reduce((s, e) => s + e.amount, 0)
  const settledOn = (day: string) => settlements.filter((s) => s.occurredOn === day).reduce((sum, s) => sum + s.amount, 0)
  const [y, m] = month.split('-').map(Number)
  return (
    <section aria-label="Calendar" className="flex flex-col gap-3">
      <div className="flex items-center gap-2">
        <Button variant="ghost" size="icon" aria-label="Previous month" onClick={() => onMonth(prev)}><ChevronLeft /></Button>
        <h3 className="min-w-36 text-center font-semibold" data-testid="calendar-month">{new Date(y!, m! - 1, 1).toLocaleDateString(locale, { month: 'long', year: 'numeric' })}</h3>
        <Button variant="ghost" size="icon" aria-label="Next month" onClick={() => onMonth(next)}><ChevronRight /></Button>
        <span className="flex-1" />
        <span className="text-sm text-muted tabular">{formatMoney(expenses.reduce((s, e) => s + e.amount, 0), group.currency, locale)} spent</span>
      </div>
      <MonthCalendar start={start} end={end} locale={locale} label="Group calendar"
        flows={(day) => ({ in: settledOn(day), out: spentOn(day) })} formatFlow={(m) => formatMoneyShort(m, group.currency, locale)}
        chips={(day) => [
          ...expenses.filter((e) => e.occurredOn === day).map((e) => ({
            id: e.id, label: `${e.description} ${formatMoneyShort(e.amount, e.currency, locale)}`, title: `${e.description}: ${formatMoney(e.amount, e.currency, locale)}`,
            onOpen: () => onOpen(e.id),
            menuLabel: <><Pencil /><span className="min-w-0 flex-1 truncate">Edit {e.description}</span><span className="pl-3 text-xs text-subtle tabular">{formatMoney(e.amount, e.currency, locale)}</span></>,
          })),
          ...settlements.filter((s) => s.occurredOn === day).map((s) => ({
            id: s.id, label: `${name(s.fromMemberId)} → ${name(s.toMemberId)} ${formatMoneyShort(s.amount, s.currency, locale)}`, tone: 'good' as const,
          })),
        ]}
        actions={(date) => onAdd ? [{ key: 'add', icon: <Plus />, label: 'Add an expense on this day', onSelect: () => onAdd(date) }] : []}
      />
    </section>
  )
}

/** "you lent ₹600" / "you borrowed ₹300" for this expense. */
function YourPart({ expense, you }: { expense: GroupExpense; you: string }) {
  const part = expense.parts.find((p) => p.memberId === you)
  if (!part) return <span className="block text-xs text-subtle">not involved</span>
  const net = part.paid - part.share
  if (net === 0) return <span className="block text-xs text-subtle">your share {formatMoney(part.share)}</span>
  return net > 0
    ? <span className="block text-xs text-good-text">you lent {formatMoney(net)}</span>
    : <span className="block text-xs text-critical">you borrowed {formatMoney(-net)}</span>
}

function SettleDialog({ payment, onClose, groupId, name, you }: {
  payment: { from: string; to: string; amount: number } | null; onClose: () => void; groupId: string; name: (id: string) => string; you: string
}) {
  const settle = useSettle(groupId)
  const [account, setAccount] = useState('')
  const { data: bookSettings } = useGroupBudget()
  const { data: myAccounts } = useAccounts()
  const payable = (myAccounts ?? []).filter((a) => a.kind !== 'OWED' && !a.archived)
  const mine = payment !== null && (payment.from === you || payment.to === you)
  const [amount, setAmount] = useState('')
  const [note, setNote] = useState('')
  const [error, setError] = useState<string>()
  const open = payment !== null

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!payment) return
    const value = parseMoney(amount || minorToInput(payment.amount, 'INR'))
    if (value === null || value <= 0) return setError('Enter the amount paid.')
    await settle.mutateAsync({ fromMemberId: payment.from, toMemberId: payment.to, amount: value, occurredOn: today(), note: note.trim() || undefined, accountId: mine && account ? account : null })
    toast.success('Payment recorded', { description: `${name(payment.from)} paid ${name(payment.to)} ${formatMoney(value)}.` })
    setAmount('')
    setNote('')
    onClose()
  }

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()} title="Record a payment"
      description={payment ? `${name(payment.from)} paid ${name(payment.to)}` : undefined}>
      <form onSubmit={submit} className="flex flex-col gap-4 p-pad" noValidate>
        <Field label="Amount" htmlFor="settle-amount" hint="A part payment is fine too.">
          <Input id="settle-amount" inputMode="decimal" className="tabular" placeholder={payment ? minorToInput(payment.amount, 'INR') : ''}
            value={amount} onChange={(e) => setAmount(e.target.value)} />
        </Field>
        {mine && bookSettings?.enabled && payable.length > 0 && (
          <Field label={payment?.from === you ? 'Paid from' : 'Received in'} htmlFor="settle-account">
            <SearchSelect id="settle-account" value={account} onChange={setAccount}
              options={[{ value: '', label: 'My usual account' }, ...accountOptions(payable)]} />
          </Field>
        )}
        <Field label="Note (optional)" htmlFor="settle-note"><Input id="settle-note" maxLength={120} placeholder="UPI" value={note} onChange={(e) => setNote(e.target.value)} /></Field>
        {error && <p className="rounded-control bg-critical/10 px-3 py-2 text-sm text-critical" role="alert">{error}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="primary" disabled={settle.isPending}>Record payment</Button>
        </div>
      </form>
    </Dialog>
  )
}
