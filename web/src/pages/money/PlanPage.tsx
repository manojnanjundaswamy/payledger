import { useState } from 'react'
import { CalendarClock, Check, PiggyBank, Plus, Receipt } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Badge, Card, Skeleton } from '@/components/ui/card'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/cn'
import { formatMoneyWhole } from '@/lib/money'
import {
  daysUntil, everyLabel, isoDate, useCommitments, useDue, usePayCommitment, usePlanner, useSafeToSpend,
  type Commitment, type Due,
} from '@/state/money'
import { useProfile } from '@/state/profile'
import { CommitmentDialog } from './CommitmentDialog'
import { SafeToSpendCard } from './SafeToSpendCard'

/** Bills and savings on a schedule: what is due this month, and what is safe to spend after them. */
export function PlanPage() {
  const locale = useProfile().data?.settings.locale ?? 'en-IN'
  const { data: commitments, isPending, isError } = useCommitments()
  const safe = useSafeToSpend()
  const [editing, setEditing] = useState<Commitment | 'new' | null>(null)
  const money = (minor: number) => formatMoneyWhole(minor, 'INR', locale)

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <p className="max-w-md text-sm text-muted">EMIs, rent, insurance, subscriptions and SIPs, counted before you spend.</p>
        <Button variant="primary" onClick={() => setEditing('new')}><Plus />Add bill or saving</Button>
      </div>

      <Card className="p-pad"><SafeToSpendCard /></Card>

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
        <DueThisMonth start={safe.data.start} end={safe.data.end} money={money} locale={locale} />
      )}

      {commitments && commitments.length > 0 && <BigCosts money={money} locale={locale} />}

      {commitments && commitments.length > 0 && (
        <section aria-label="Your plan" className="flex flex-col gap-2">
          <h2 className="text-sm font-medium text-muted">Your plan</h2>
          <Card className="divide-y divide-border p-0">
            {commitments.map((c) => (
              <button key={c.id} type="button" onClick={() => setEditing(c)} aria-label={`Edit ${c.name}`}
                className="flex w-full items-center gap-3 px-pad py-3 text-left hover:bg-surface-2 first:rounded-t-card last:rounded-b-card">
                <KindIcon kind={c.kind} />
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-medium">{c.name}</span>
                  <span className="block text-xs text-subtle">
                    {c.endsOn === c.firstDue
                      ? `Once, on ${dateLabel(c.firstDue, locale)}`
                      : <>{everyLabel(c.everyMonths)} on day {Number(c.firstDue.slice(8))}{c.endsOn && ` · until ${dateLabel(c.endsOn, locale)}`}</>}
                  </span>
                </span>
                <span className="tabular shrink-0 font-medium">{money(c.amount)}</span>
              </button>
            ))}
          </Card>
        </section>
      )}

      <CommitmentDialog open={editing !== null} onOpenChange={(o) => !o && setEditing(null)}
        commitment={editing === 'new' || editing === null ? undefined : editing} />
    </div>
  )
}

/** This budget month's due dates, plus anything from the month before that is still unpaid. */
function DueThisMonth({ start, end, money, locale }: { start: string; end: string; money: (m: number) => string; locale: string }) {
  const [y, m, d] = start.split('-').map(Number)
  const { data: due = [], isPending } = useDue(isoDate(new Date(y!, m! - 2, d!)), end)
  const pay = usePayCommitment()
  const rows = due.filter((x) => x.dueDate >= start || !x.paid)

  async function markPaid(x: Due) {
    try {
      await pay.mutateAsync({ id: x.commitmentId, dueDate: x.dueDate })
      toast.success(`${x.name} paid`, { description: x.kind === 'BILL' ? 'Recorded as an expense.' : 'Recorded as a transfer.' })
    } catch (err) {
      toast.error(err instanceof ApiError && err.code === 'already-paid' ? `${x.name} was already paid` : 'Could not record it. Try again.')
    }
  }

  return (
    <section aria-label="Due this month" className="flex flex-col gap-2">
      <h2 className="text-sm font-medium text-muted">Due this month</h2>
      {isPending ? <Skeleton className="h-24" /> : rows.length === 0 ? (
        <Card className="p-pad text-sm text-muted">Nothing due this month.</Card>
      ) : (
        <Card className="divide-y divide-border p-0">
          {rows.map((x) => {
            const days = daysUntil(x.dueDate)
            return (
              <div key={`${x.commitmentId}/${x.dueDate}`} className="flex items-center gap-3 px-pad py-3" data-testid={`due-${x.name}`}>
                <DateChip iso={x.dueDate} locale={locale} />
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-medium">{x.name}</span>
                  <span className={cn('block text-xs', !x.paid && days < 0 ? 'text-critical' : 'text-subtle')}>
                    {x.paid ? 'Paid' : days === 0 ? 'Due today' : days > 0 ? `In ${days} day${days === 1 ? '' : 's'}` : `Overdue by ${-days} day${days === -1 ? '' : 's'}`}
                  </span>
                </span>
                <span className="tabular shrink-0 text-sm font-medium">{money(x.amount)}</span>
                {x.paid
                  ? <Badge tone="good"><Check className="size-3" />Paid</Badge>
                  : <Button size="sm" variant="outline" onClick={() => markPaid(x)} disabled={pay.isPending}>Mark paid</Button>}
              </div>
            )
          })}
        </Card>
      )}
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
