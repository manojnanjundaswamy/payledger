import { useState, type FormEvent } from 'react'
import { ArrowRight, HandCoins, MessageCircle, Plus, Receipt } from 'lucide-react'
import { whatsAppShare } from '@/lib/whatsapp'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card, Skeleton } from '@/components/ui/card'
import { Input } from '@/components/ui/controls'
import { Dialog } from '@/components/ui/overlay'
import { cn } from '@/lib/cn'
import { formatMoney, minorToInput, parseMoney } from '@/lib/money'
import { Field, dayLabel, today } from '@/pages/money/parts'
import { useActivity, useBalances, useDeleteSettlement, useSettle, type Group, type GroupExpense } from '@/state/groups'
import { ExpenseDialog } from './ExpenseDialog'

/** A group's money: where you stand, who should pay whom, and everything spent and settled. */
export function GroupMoney({ group }: { group: Group }) {
  const { data: activity, isPending } = useActivity(group.id)
  const { data: balances } = useBalances(group.id)
  const [editing, setEditing] = useState<GroupExpense | 'new' | null>(null)
  const [settling, setSettling] = useState<{ from: string; to: string; amount: number } | null>(null)
  const deleteSettlement = useDeleteSettlement(group.id)
  const name = (id: string) => {
    const m = group.members.find((x) => x.id === id)
    return m ? (m.you ? 'You' : m.name) : 'Someone who left'
  }
  const mine = balances?.balance?.[group.you] ?? 0

  type Row = { kind: 'expense'; date: string; expense: GroupExpense } | { kind: 'settlement'; date: string; id: string; from: string; to: string; amount: number; note: string | null }
  const rows: Row[] = [
    ...(activity?.expenses ?? []).map((e) => ({ kind: 'expense' as const, date: e.occurredOn, expense: e })),
    ...(activity?.settlements ?? []).map((s) => ({ kind: 'settlement' as const, date: s.occurredOn, id: s.id, from: s.fromMemberId, to: s.toMemberId, amount: s.amount, note: s.note })),
  ].sort((a, b) => b.date.localeCompare(a.date))

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
        {!group.archived && <Button variant="primary" onClick={() => setEditing('new')}><Plus />Add expense</Button>}
      </Card>

      {balances?.known && balances.payments.length > 0 && (
        <section aria-label="Settle up" className="flex flex-col gap-2">
          <h3 className="text-sm font-medium text-muted">Settle up in {balances.payments.length} payment{balances.payments.length === 1 ? '' : 's'}</h3>
          <Card className="divide-y divide-border p-0">
            {balances.payments.map((p) => (
              <div key={`${p.from}-${p.to}`} className="flex flex-wrap items-center gap-3 px-pad py-3 text-sm" data-testid={`payment-${name(p.from)}-${name(p.to)}`}>
                <span className="font-medium">{name(p.from)}</span><ArrowRight className="size-4 text-muted" /><span className="font-medium">{name(p.to)}</span>
                <span className="flex-1 tabular">{formatMoney(p.amount)}</span>
                {/* A friendly nudge, written for them; they pay by UPI and the payment is recorded here. */}
                {p.to === group.you && (
                  <Button asChild size="sm" variant="ghost">
                    <a href={whatsAppShare(`Hi ${name(p.from)}, a reminder for ${group.name}: you owe me ${formatMoney(p.amount)}. Thanks!`)}
                      target="_blank" rel="noopener noreferrer" aria-label={`Remind ${name(p.from)} on WhatsApp`}><MessageCircle />Remind</a>
                  </Button>
                )}
                {!group.archived && <Button size="sm" variant="outline" onClick={() => setSettling(p)}><HandCoins />Record payment</Button>}
              </div>
            ))}
          </Card>
        </section>
      )}

      <section aria-label="Activity" className="flex flex-col gap-2">
        <h3 className="text-sm font-medium text-muted">Activity</h3>
        {isPending ? <Skeleton className="h-24" /> : rows.length === 0 ? (
          <Card className="p-pad text-sm text-muted">No expenses yet. Add the first one: who paid, and who it was for.</Card>
        ) : (
          <Card className="divide-y divide-border p-0">
            {rows.map((r) => r.kind === 'expense' ? (
              <button key={r.expense.id} type="button" onClick={() => setEditing(r.expense)} aria-label={`Edit ${r.expense.description}`}
                className="flex w-full items-center gap-3 px-pad py-3 text-left hover:bg-surface-2">
                <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-surface-2 text-muted"><Receipt className="size-4" /></span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-medium">{r.expense.description}</span>
                  <span className="block truncate text-xs text-subtle">
                    {dayLabel(r.date)} · {r.expense.parts.filter((p) => p.paid > 0).map((p) => name(p.memberId)).join(' & ')} paid
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

      <ExpenseDialog open={editing !== null} onOpenChange={(o) => !o && setEditing(null)} groupId={group.id}
        members={group.members} you={group.you} expense={editing === 'new' || editing === null ? undefined : editing} />
      <SettleDialog payment={settling} onClose={() => setSettling(null)} groupId={group.id} name={name} />
    </div>
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

function SettleDialog({ payment, onClose, groupId, name }: {
  payment: { from: string; to: string; amount: number } | null; onClose: () => void; groupId: string; name: (id: string) => string
}) {
  const settle = useSettle(groupId)
  const [amount, setAmount] = useState('')
  const [note, setNote] = useState('')
  const [error, setError] = useState<string>()
  const open = payment !== null

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!payment) return
    const value = parseMoney(amount || minorToInput(payment.amount, 'INR'))
    if (value === null || value <= 0) return setError('Enter the amount paid.')
    await settle.mutateAsync({ fromMemberId: payment.from, toMemberId: payment.to, amount: value, occurredOn: today(), note: note.trim() || undefined })
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
