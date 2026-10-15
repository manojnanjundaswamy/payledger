import { Fragment, useEffect, useState } from 'react'
import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { CalendarClock, CalendarDays, Check, History, List, PiggyBank, Plus, Receipt, Sparkles, Trash2, Unlink } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Badge, Card, Skeleton } from '@/components/ui/card'
import { Segmented } from '@/components/ui/controls'
import { Dialog } from '@/components/ui/overlay'
import { ApiError, api } from '@/lib/api'
import { cn } from '@/lib/cn'
import { formatMoneyWhole } from '@/lib/money'
import {
  daysUntil, everyLabel, isoDate, useCommitments, useDeleteTransaction, useDue, useLinkPayment, usePayCommitment, usePlanner, useSafeToSpend,
  useSpaceId, useTransactions, useUnlinkPayment, type Commitment, type Due, type Transaction,
} from '@/state/money'
import { useProfile } from '@/state/profile'
import { BillCalendar } from './BillCalendar'
import { MonthFlow } from './MonthFlow'
import { BillSuggestions } from './BillSuggestions'
import { CommitmentDialog } from './CommitmentDialog'
import { SafeToSpendCard } from './SafeToSpendCard'

/** Bills and savings on a schedule: what is due this month, and what is safe to spend after them. */
export function PlanPage() {
  const locale = useProfile().data?.settings.locale ?? 'en-IN'
  const { data: commitments, isPending, isError } = useCommitments()
  const safe = useSafeToSpend()
  const [editing, setEditing] = useState<Commitment | 'new' | null>(null)
  // A new bill's first due date, when it was started from a day on the calendar.
  const [newDue, setNewDue] = useState<string>()
  const money = (minor: number) => formatMoneyWhole(minor, undefined, locale)
  const { open, due } = useSearch({ from: '/app/plan' })
  const navigate = useNavigate({ from: '/plan' })
  const openById = (id: string) => {
    const c = commitments?.find((x) => x.id === id)
    if (c) setEditing(c)
  }
  // ?due=<date>: start a bill due that day, then drop it from the URL so a refresh doesn't reopen it.
  useEffect(() => {
    if (!due) return
    setNewDue(due)
    setEditing('new')
    void navigate({ search: (s) => ({ ...s, due: undefined }), replace: true })
  }, [due]) // eslint-disable-line react-hooks/exhaustive-deps
  // ?open=<id>: open that bill once the plan has loaded.
  useEffect(() => {
    if (open && commitments) openById(open)
  }, [open, commitments])

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <p className="max-w-md text-sm text-muted">EMIs, rent, insurance, subscriptions and SIPs, counted before you spend.</p>
        <Button variant="primary" onClick={() => setEditing('new')}><Plus />Add bill or saving</Button>
      </div>

      <Card className="p-pad"><SafeToSpendCard /></Card>

      {commitments && commitments.length > 0 && <MonthFlow />}

      {isError && <Card className="p-pad text-sm text-critical">Couldn't load your plan. Refresh to try again.</Card>}
      {isPending && <Skeleton className="h-40" />}

      {commitments && commitments.length === 0 && (
        <Card className="flex flex-col items-center gap-3 px-pad py-12 text-center">
          <CalendarClock className="size-8 text-muted" />
          <p className="font-medium">Nothing on your plan yet</p>
          <p className="max-w-sm text-sm text-muted">Add your rent, EMIs, insurance and SIPs once. They show up when they're due, and safe to spend keeps money aside for them.</p>
          <Button variant="primary" onClick={() => setEditing('new')}><Plus />Add bill or saving</Button>
        </Card>
      )}

      {commitments && commitments.length > 0 && safe.data && (
        <DueThisMonth start={safe.data.start} end={safe.data.end} money={money} locale={locale} onOpen={openById}
          onAdd={(date) => { setNewDue(date); setEditing('new') }} />
      )}

      {commitments && commitments.length > 0 && <BigCosts money={money} locale={locale} />}

      <BillSuggestions money={money} />

      {commitments && commitments.length > 0 && (
        <section aria-label="Your plan" className="flex flex-col gap-2">
          <h2 className="text-sm font-medium text-muted">Your plan</h2>
          <Card className="divide-y divide-border p-0">
            {commitments.map((c) => (
              <div key={c.id} className="flex items-center gap-1 pr-2 hover:bg-surface-2 first:rounded-t-card last:rounded-b-card">
              <button type="button" onClick={() => setEditing(c)} aria-label={`Edit ${c.name}`}
                className="flex min-w-0 flex-1 items-center gap-3 py-3 pl-pad text-left">
                <KindIcon kind={c.kind} />
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-medium">{c.name}</span>
                  <span className="block text-xs text-subtle">
                    {c.endsOn === c.firstDue
                      ? `Once, on ${dateLabel(c.firstDue, locale)}`
                      : <>{everyLabel(c.everyMonths)} on day {Number(c.firstDue.slice(8))}{c.endsOn && ` · until ${dateLabel(c.endsOn, locale)}`}{c.auto && ' · records itself'}</>}
                  </span>
                </span>
                <span className="tabular shrink-0 font-medium">{money(c.amount)}</span>
              </button>
              {/* What was actually paid: its category from its account. */}
              <Link to="/transactions" search={{ account: c.accountId, ...(c.categoryId ? { category: c.categoryId } : {}) }}
                aria-label={`${c.name} payments`} title="Payments"
                className="flex size-8 shrink-0 items-center justify-center rounded-control text-subtle hover:bg-surface-3 hover:text-fg">
                <History className="size-4" />
              </Link>
              </div>
            ))}
          </Card>
        </section>
      )}

      <CommitmentDialog open={editing !== null} onOpenChange={(o) => {
        if (o) return
        setEditing(null)
        setNewDue(undefined)
        if (open) void navigate({ search: {}, replace: true })
      }}
        commitment={editing === 'new' || editing === null ? undefined : editing} initialDue={newDue} />
    </div>
  )
}

/** This budget month's due dates, plus anything from the month before that is still unpaid. */
function DueThisMonth({ start, end, money, locale, onOpen, onAdd }: {
  start: string; end: string; money: (m: number) => string; locale: string; onOpen: (id: string) => void; onAdd: (date: string) => void
}) {
  const [y, m, d] = start.split('-').map(Number)
  const { data: due = [], isPending } = useDue(isoDate(new Date(y!, m! - 2, d!)), end)
  const pay = usePayCommitment()
  const link = useLinkPayment()
  const [linking, setLinking] = useState<Due | null>(null)
  const [unlinking, setUnlinking] = useState<Due | null>(null)
  const [deleting, setDeleting] = useState<Entry | null>(null)
  const rows = due.filter((x) => x.dueDate >= start || !x.paid)
  const [view, setView] = useState<'list' | 'calendar'>(() => {
    try { return localStorage.getItem('plan-view') === 'calendar' ? 'calendar' : 'list' } catch { return 'list' }
  })
  const choose = (v: 'list' | 'calendar') => {
    setView(v)
    try { localStorage.setItem('plan-view', v) } catch { /* a private window: just don't remember */ }
  }

  async function markPaid(x: Due) {
    try {
      await pay.mutateAsync({ id: x.commitmentId, dueDate: x.dueDate })
      toast.success(`${x.name} paid`, { description: x.kind === 'BILL' ? 'Recorded as an expense.' : 'Recorded as a transfer.' })
    } catch (err) {
      toast.error(err instanceof ApiError && err.code === 'already-paid' ? `${x.name} was already paid` : 'Could not record it. Try again.')
    }
  }

  return (
    <section aria-label="Due this month" id="due" className="flex scroll-mt-20 flex-col gap-2">
      <div className="flex items-center justify-between gap-2">
        <h2 className="text-sm font-medium text-muted">Due this month</h2>
        <Segmented label="View" value={view} onChange={choose}
          options={[{ value: 'list', label: <><List />List</> }, { value: 'calendar', label: <><CalendarDays />Calendar</> }]} />
      </div>
      {isPending ? <Skeleton className="h-24" /> : view === 'calendar' ? (
        <BillCalendar start={start} end={end} due={due.filter((x) => x.dueDate >= start)} money={money} locale={locale} onOpen={onOpen} onAdd={onAdd} />
      ) : rows.length === 0 ? (
        <Card className="p-pad text-sm text-muted">Nothing due this month.</Card>
      ) : (
        <Card className="divide-y divide-border p-0">
          {rows.map((x) => {
            const days = daysUntil(x.dueDate)
            return (
              <Fragment key={`${x.commitmentId}/${x.dueDate}`}>
              <div className="flex flex-wrap items-center gap-x-3 gap-y-2 px-pad py-3" data-testid={`due-${x.name}`}>
                <DateChip iso={x.dueDate} locale={locale} />
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-medium">{x.name}</span>
                  <span className={cn('block text-xs', !x.paid && days < 0 ? 'text-critical' : 'text-subtle')}>
                    {x.paid ? 'Paid' : days === 0 ? 'Due today' : days > 0 ? `In ${days} day${days === 1 ? '' : 's'}` : `Overdue by ${-days} day${days === -1 ? '' : 's'}`}
                  </span>
                </span>
                <span className="tabular shrink-0 text-sm font-medium">{money(x.amount)}</span>
                {x.paid ? (
                  <span className="flex items-center gap-1 max-sm:ml-auto">
                    {/* Paid: the badge opens the payment; unlinking keeps the transaction. */}
                    <Link to="/transactions" search={{ open: x.transactionId! }} aria-label={`Open the payment of ${x.name}`}>
                      <Badge tone="good"><Check className="size-3" />Paid</Badge>
                    </Link>
                    <Button size="sm" variant="ghost" aria-label={`Unlink the payment of ${x.name}`} title="Not this payment, or paid by mistake?"
                      onClick={() => setUnlinking(x)}><Unlink /></Button>
                  </span>
                ) : (
                  // On a phone the buttons take their own line, so the name keeps its room.
                  <span className="flex items-center justify-end gap-1 max-sm:basis-full">
                    <Button size="sm" variant="ghost" onClick={() => setLinking(x)} title="Already paid it? Pick the transaction">Link</Button>
                    <Button size="sm" variant="outline" onClick={() => markPaid(x)} disabled={pay.isPending}>Mark paid</Button>
                  </span>
                )}
              </div>
              {!x.paid && x.suggestion && (
                <div className="flex flex-wrap items-center gap-2 bg-accent-soft/50 px-pad py-2 text-sm" data-testid={`suggestion-${x.name}`}>
                  <Sparkles className="size-4 text-accent" />
                  <span className="min-w-0 flex-1">
                    Looks paid: <span className="font-medium">{x.suggestion.description}</span>
                    <span className="text-muted"> · {new Date(`${x.suggestion.occurredOn}T00:00`).toLocaleDateString(locale, { day: 'numeric', month: 'short' })} · {money(x.suggestion.amount)}</span>
                  </span>
                  <Button size="sm" variant="ghost" onClick={() => setDeleting({ name: x.name, transactionId: x.suggestion!.transactionId, description: x.suggestion!.description, amount: x.suggestion!.amount })}>
                    Delete it
                  </Button>
                  <Button size="sm" variant="primary" onClick={() => link.mutate({ id: x.commitmentId, dueDate: x.dueDate, transactionId: x.suggestion!.transactionId },
                    { onSuccess: () => toast.success(`${x.name} marked paid`, { description: `With ${x.suggestion!.description}.` }) })}>That's it</Button>
                </div>
              )}
              </Fragment>
            )
          })}
        </Card>
      )}
      <LinkPaymentDialog due={linking} onClose={() => setLinking(null)} money={money} locale={locale} />
      <UnlinkDialog due={unlinking} onClose={() => setUnlinking(null)} money={money} />
      <DeleteEntryDialog entry={deleting} onClose={() => setDeleting(null)} money={money} />
    </section>
  )
}

/**
 * The annual planner: insurance, school fees, Diwali, a wedding. Each is spread over the months before
 * it, so the month it lands in isn't a shock; safe to spend already keeps that money aside.
 */
function BigCosts({ money, locale }: { money: (m: number) => string; locale: string }) {
  const { data: planner } = usePlanner()
  if (!planner || planner.items.length === 0) {
    return (
      <section aria-label="Big costs ahead" className="flex flex-col gap-2">
        <h2 className="text-sm font-medium text-muted">Big costs ahead</h2>
        <Card className="p-pad text-sm text-muted">
          Add yearly or one-off costs (insurance, school fees, Diwali, a wedding) with "Every year" or "Only once". You'll see
          a small amount to set aside each month instead of a shock when they're due.
        </Card>
      </section>
    )
  }
  return (
    <section aria-label="Big costs ahead" className="flex flex-col gap-2">
      <div className="flex items-baseline justify-between gap-2">
        <h2 className="text-sm font-medium text-muted">Big costs ahead</h2>
        <span className="text-sm text-muted" data-testid="set-aside-total">
          Set aside <span className="font-medium text-fg tabular">{money(planner.monthlySetAside)}</span> this month
        </span>
      </div>
      <Card className="divide-y divide-border p-0">
        {planner.items.map((p) => (
          <div key={p.commitmentId} className="flex items-center gap-3 px-pad py-3" data-testid={`planned-${p.name}`}>
            <DateChip iso={p.nextDue} locale={locale} />
            <span className="min-w-0 flex-1">
              <span className="block truncate font-medium">{p.name}</span>
              <span className="block text-xs text-subtle">
                {money(p.amount)} {p.oneOff ? 'once' : everyLabel(p.everyMonths).toLowerCase()}
                {' · '}
                {p.monthsLeft === 0 ? (p.paid ? 'paid this month' : 'due this month') : `in ${p.monthsLeft} month${p.monthsLeft === 1 ? '' : 's'}`}
              </span>
            </span>
            <span className="text-right">
              {p.monthsLeft > 0
                ? <><span className="block font-medium tabular">{money(p.monthlySetAside)}</span><span className="block text-xs text-subtle">a month</span></>
                : p.covered ? <Badge tone="good"><Check className="size-3" />Set aside</Badge> : <Badge>Due now</Badge>}
            </span>
          </div>
        ))}
      </Card>
    </section>
  )
}

function KindIcon({ kind }: { kind: Commitment['kind'] }) {
  const Icon = kind === 'BILL' ? Receipt : PiggyBank
  return <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-surface-2 text-muted"><Icon className="size-4" /></span>
}

function DateChip({ iso, locale }: { iso: string; locale: string }) {
  const [y, m, d] = iso.split('-').map(Number)
  const date = new Date(y!, m! - 1, d!)
  return (
    <span className="flex w-11 shrink-0 flex-col items-center rounded-control border border-border py-1 leading-none">
      <span className="text-[0.625rem] text-subtle uppercase">{date.toLocaleDateString(locale, { month: 'short' })}</span>
      <span className="text-[0.9375rem] font-semibold">{d}</span>
    </span>
  )
}

function dateLabel(iso: string, locale: string): string {
  const [y, m, d] = iso.split('-').map(Number)
  return new Date(y!, m! - 1, d!).toLocaleDateString(locale, { day: 'numeric', month: 'short', year: 'numeric' })
}

/**
 * Pick the transaction that paid a due date: the bill's category (or transfers for a saving), around the date. For
 * when you entered the expense yourself and it wasn't matched (a different amount, a later date).
 */
interface Entry { name: string; transactionId: string; description: string; amount: number }

/** Deletes an expense by id: it needs the current version, so it reads it first. */
function useDeleteEntry() {
  const spaceId = useSpaceId()
  const remove = useDeleteTransaction()
  return {
    isPending: remove.isPending,
    run: async (transactionId: string) => {
      const t = await api<Transaction>(`/api/core/spaces/${spaceId}/transactions/${transactionId}`)
      await remove.mutateAsync({ id: t.id, version: t.version })
    },
  }
}

/**
 * "Paid" undone: was it the wrong transaction (keep it, just unlink), or was the payment itself a mistake (delete the
 * expense "Mark paid" recorded, so it doesn't stay behind in your spending)?
 */
function UnlinkDialog({ due, onClose, money }: { due: Due | null; onClose: () => void; money: (m: number) => string }) {
  const unlink = useUnlinkPayment()
  const del = useDeleteEntry()
  if (!due) return null
  const keep = () => unlink.mutate({ id: due.commitmentId, dueDate: due.dueDate }, { onSuccess: () => { toast(`${due.name} is unpaid again`, { description: 'The transaction stays in your list.' }); onClose() } })
  const remove = async () => {
    await unlink.mutateAsync({ id: due.commitmentId, dueDate: due.dueDate })
    await del.run(due.transactionId!)
    toast(`${due.name} is unpaid again`, { description: 'Its payment was deleted.' })
    onClose()
  }
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={`Unlink ${due.name}'s payment?`}
      description={`It was paid with a transaction of ${money(due.amount)}. Was that the wrong transaction, or a payment you didn't make?`}>
      <div className="flex flex-col gap-2 p-pad">
        <Button variant="outline" className="justify-start" onClick={keep} disabled={unlink.isPending}>
          <Unlink />Wrong transaction: unlink it, keep the transaction
        </Button>
        <Button variant="outline" className="justify-start text-critical" onClick={() => void remove()} disabled={unlink.isPending || del.isPending}>
          <Trash2 />Not paid: unlink and delete that expense
        </Button>
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
      </div>
    </Dialog>
  )
}

/** A suggested payment that shouldn't exist at all (left behind by a mistaken "Mark paid"): delete it here. */
function DeleteEntryDialog({ entry, onClose, money }: { entry: Entry | null; onClose: () => void; money: (m: number) => string }) {
  const del = useDeleteEntry()
  if (!entry) return null
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={`Delete ${entry.description}?`}
      description={`${money(entry.amount)}. It leaves your spending and balances; ${entry.name} stays due.`}>
      <div className="flex justify-end gap-2 p-pad">
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        <Button variant="primary" className="bg-critical text-white" disabled={del.isPending}
          onClick={() => void del.run(entry.transactionId).then(() => { toast(`${entry.description} deleted`); onClose() })}><Trash2 />Delete</Button>
      </div>
    </Dialog>
  )
}

function LinkPaymentDialog({ due, onClose, money, locale }: { due: Due | null; onClose: () => void; money: (m: number) => string; locale: string }) {
  const { data: commitments = [] } = useCommitments()
  const link = useLinkPayment()
  const c = commitments.find((x) => x.id === due?.commitmentId)
  const around = (days: number) => {
    if (!due) return ''
    const d = new Date(`${due.dueDate}T00:00`)
    d.setDate(d.getDate() + days)
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  }
  const { data } = useTransactions(c ? {
    from: around(-45), to: around(45), kind: c.kind === 'BILL' ? 'EXPENSE' : 'TRANSFER',
    categoryId: c.kind === 'BILL' ? c.categoryId ?? undefined : undefined, accountId: c.kind === 'SAVING' ? c.toAccountId ?? undefined : undefined,
  } : { from: '1900-01-01', to: '1900-01-01' })
  const items = (data?.pages.flatMap((p) => p.items) ?? []).slice(0, 30)
  return (
    <Dialog open={due !== null} onOpenChange={(o) => !o && onClose()} title={due ? `Which transaction paid ${due.name}?` : 'Link a payment'}
      description={due ? `Due ${new Date(`${due.dueDate}T00:00`).toLocaleDateString(locale, { day: 'numeric', month: 'short', year: 'numeric' })} · ${money(due.amount)}` : undefined}>
      <div className="flex flex-col gap-2 p-pad">
        {items.length === 0 ? <p className="text-sm text-muted">Nothing in {c?.kind === 'BILL' ? 'its category' : 'that account'} within six weeks of the date. Use Mark paid to record it.</p> : (
          <ul className="divide-y divide-border rounded-control border border-border">
            {items.map((t) => (
              <li key={t.id}>
                <button type="button" className="flex w-full items-center gap-3 px-3 py-2 text-left text-sm hover:bg-surface-2" aria-label={`It was ${t.description}`}
                  onClick={() => due && link.mutate({ id: due.commitmentId, dueDate: due.dueDate, transactionId: t.id }, {
                    onSuccess: () => { toast.success(`${due.name} marked paid`, { description: `With ${t.description}.` }); onClose() },
                    onError: (err) => toast.error(err instanceof ApiError ? err.message : 'Could not link it. Try again.'),
                  })}>
                  <span className="w-16 shrink-0 text-xs text-subtle">{new Date(`${t.occurredOn}T00:00`).toLocaleDateString(locale, { day: 'numeric', month: 'short' })}</span>
                  <span className="min-w-0 flex-1 truncate">{t.description}</span>
                  <span className="tabular">{money(t.amount)}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </Dialog>
  )
}
