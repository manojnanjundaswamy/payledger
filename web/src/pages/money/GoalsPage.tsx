import { useEffect, useState, type FormEvent } from 'react'
import { Link } from '@tanstack/react-router'
import { Archive, ArchiveRestore, CalendarDays, Goal as GoalIcon, Landmark, Pencil, Plus, Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Badge, Card, Skeleton } from '@/components/ui/card'
import { Input, Segmented, Switch } from '@/components/ui/controls'
import { Dialog } from '@/components/ui/overlay'
import { SearchSelect } from '@/components/ui/search-select'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/cn'
import { formatMoneyWhole, minorToInput, parseMoney } from '@/lib/money'
import { useAccounts } from '@/state/money'
import {
  useContribute, useCreateGoal, useDeleteContribution, useDeleteGoal, useGoalContributions, useGoals, useUpdateGoal, type Goal,
} from '@/state/goals'
import { useProfile } from '@/state/profile'
import { Field, accountOptions } from './parts'

const EMOJIS = ['🎯', '🏖️', '🏠', '🚗', '📱', '💍', '🎓', '🪔', '🛟', '✈️']

/** Savings goals: what you're saving for, how far along, and what it needs each month to get there on time. */
export function GoalsPage() {
  const [showArchived, setShowArchived] = useState(false)
  const { data: goals, isPending, isError } = useGoals(showArchived)
  const [editing, setEditing] = useState<Goal | 'new' | null>(null)
  const [adding, setAdding] = useState<Goal | null>(null)
  const locale = useProfile().data?.settings.locale ?? 'en-IN'
  const money = (m: number) => formatMoneyWhole(m, 'INR', locale)
  const live = (goals ?? []).filter((g) => !g.archived)
  const monthly = live.reduce((s, g) => s + (g.perMonth ?? 0), 0)
  const saved = live.reduce((s, g) => s + (g.saved ?? 0), 0)
  const target = live.reduce((s, g) => s + g.target, 0)

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <p className="max-w-md text-sm text-muted">A trip, a phone, Diwali, an emergency fund: set an amount and a date, and see what it needs each month.</p>
        <Button variant="primary" onClick={() => setEditing('new')}><Plus />New goal</Button>
      </div>

      {isError && <Card className="p-pad text-sm text-critical">Couldn't load your goals. Refresh to try again.</Card>}
      {isPending && <Skeleton className="h-40" />}

      {goals && live.length === 0 && !showArchived && (
        <Card className="flex flex-col items-center gap-3 px-pad py-12 text-center">
          <GoalIcon className="size-8 text-muted" />
          <p className="font-medium">No goals yet</p>
          <p className="max-w-sm text-sm text-muted">Saving for something? Give it a name, an amount and a date; we'll work out the monthly amount and keep you on track.</p>
          <Button variant="primary" onClick={() => setEditing('new')}><Plus />New goal</Button>
        </Card>
      )}

      {live.length > 0 && (
        <section aria-label="All goals" className="grid gap-3 sm:grid-cols-3">
          {[
            { label: 'Saved so far', value: money(saved), sub: `of ${money(target)}` },
            { label: 'Needed each month', value: money(monthly), sub: 'to reach every goal on time' },
            { label: 'On track', value: `${live.filter((g) => g.status === 'ON_TRACK' || g.status === 'REACHED').length} of ${live.length}`, sub: live.some((g) => g.status === 'BEHIND') ? 'some need a top-up' : 'nicely done' },
          ].map((k) => (
            <Card key={k.label} className="p-pad">
              <div className="text-xs text-muted">{k.label}</div>
              <div className="text-2xl font-semibold tracking-tight tabular" data-testid={`goals-${k.label.toLowerCase().replace(/ /g, '-')}`}>{k.value}</div>
              <div className="text-xs text-subtle">{k.sub}</div>
            </Card>
          ))}
        </section>
      )}

      {goals && goals.length > 0 && (
        <div className="grid gap-3 sm:grid-cols-2">
          {goals.map((g) => <GoalCard key={g.id} goal={g} money={money} locale={locale} onEdit={() => setEditing(g)} onAdd={() => setAdding(g)} />)}
        </div>
      )}

      <label className="flex items-center gap-2 self-start text-sm text-muted">
        <Switch checked={showArchived} onCheckedChange={setShowArchived} label="Show archived goals" />Show archived goals
      </label>

      <GoalDialog open={editing !== null} onOpenChange={(o) => !o && setEditing(null)} goal={editing === 'new' || editing === null ? undefined : editing} />
      <AddMoneyDialog goal={adding} onClose={() => setAdding(null)} money={money} />
    </div>
  )
}

const STATUS: Record<Goal['status'], { label: string; tone: string }> = {
  REACHED: { label: 'Reached', tone: 'bg-good/15 text-good-text' },
  ON_TRACK: { label: 'On track', tone: 'bg-good/10 text-good-text' },
  BEHIND: { label: 'Behind', tone: 'bg-warning/15 text-fg' },
  OPEN: { label: 'No date', tone: 'bg-surface-2 text-muted' },
}

function GoalCard({ goal: g, money, locale, onEdit, onAdd }: { goal: Goal; money: (m: number) => string; locale: string; onEdit: () => void; onAdd: () => void }) {
  const { data: accounts = [] } = useAccounts(true)
  const update = useUpdateGoal()
  const remove = useDeleteGoal()
  const pct = g.saved === null ? 0 : Math.min(1, g.saved / g.target)
  const account = accounts.find((a) => a.id === g.accountId)
  const status = STATUS[g.status]
  const fields = { name: g.name, emoji: g.emoji, target: g.target, targetDate: g.targetDate, accountId: g.accountId }
  return (
    <Card className={cn('flex flex-col gap-4 p-pad', g.archived && 'opacity-70')} data-testid={`goal-${g.name}`}>
      <div className="flex items-start gap-3">
        <Ring value={pct} emoji={g.emoji ?? '🎯'} />
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <h3 className="truncate font-semibold">{g.name}</h3>
            {g.archived ? <Badge>Archived</Badge> : <span className={cn('rounded-full px-2 py-0.5 text-xs font-medium', status.tone)} data-testid="goal-status">{status.label}</span>}
          </div>
          <p className="mt-0.5 text-sm tabular">
            <span className="font-medium" data-testid="goal-saved">{g.saved === null ? '—' : money(g.saved)}</span>
            <span className="text-muted"> of {money(g.target)}</span>
            <span className="text-subtle"> · {Math.round(pct * 100)}%</span>
          </p>
          <p className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-0.5 text-xs text-muted">
            {g.targetDate && <span className="flex items-center gap-1"><CalendarDays className="size-3.5" />by {new Date(`${g.targetDate}T00:00`).toLocaleDateString(locale, { month: 'short', year: 'numeric' })}</span>}
            {account && <Link to="/transactions" search={{ account: account.id }} className="flex items-center gap-1 hover:text-fg hover:underline"><Landmark className="size-3.5" />{account.name}</Link>}
          </p>
        </div>
      </div>
      {!g.archived && g.status !== 'REACHED' && (
        <p className="rounded-control bg-surface-2 px-3 py-2 text-sm" data-testid="goal-advice">
          {g.perMonth !== null
            ? <>Put aside <span className="font-semibold tabular">{money(g.perMonth)}</span> a month{g.status === 'BEHIND' && g.behindBy ? <> · <span className="text-muted">{money(g.behindBy)} behind where you'd be</span></> : null}</>
            : 'Add a date to see what it needs each month.'}
        </p>
      )}
      {g.status === 'REACHED' && !g.archived && <p className="rounded-control bg-good/10 px-3 py-2 text-sm text-good-text">You got there. Archive it to keep it as history.</p>}
      <div className="flex flex-wrap items-center gap-1">
        {!g.archived && !g.accountId && <Button size="sm" variant="primary" onClick={onAdd}><Plus />Add money</Button>}
        <span className="flex-1" />
        <Button size="sm" variant="ghost" aria-label={`Edit ${g.name}`} onClick={onEdit}><Pencil /></Button>
        <Button size="sm" variant="ghost" aria-label={g.archived ? `Bring back ${g.name}` : `Archive ${g.name}`}
          onClick={() => update.mutate({ id: g.id, version: g.version, fields, archived: !g.archived }, {
            onError: (err) => toast.error(err instanceof ApiError && err.code === 'plan-limit' ? 'The Free plan has one active goal: archive another first, or try Premium.' : 'Could not do that. Try again.'),
          })}>
          {g.archived ? <ArchiveRestore /> : <Archive />}
        </Button>
        {g.archived && <Button size="sm" variant="ghost" aria-label={`Delete ${g.name}`} onClick={() => remove.mutate(g.id)}><Trash2 /></Button>}
      </div>
    </Card>
  )
}

/** Progress as a ring with the goal's emoji in the middle. */
function Ring({ value, emoji }: { value: number; emoji: string }) {
  const R = 22
  const C = 2 * Math.PI * R
  return (
    <span className="relative flex size-14 shrink-0 items-center justify-center" aria-hidden>
      <svg viewBox="0 0 52 52" className="absolute inset-0 -rotate-90">
        <circle cx="26" cy="26" r={R} fill="none" stroke="var(--surface-2)" strokeWidth="5" />
        <circle cx="26" cy="26" r={R} fill="none" stroke={value >= 1 ? 'var(--good)' : 'var(--flow-in)'} strokeWidth="5" strokeLinecap="round"
          strokeDasharray={`${C * value} ${C}`} className="transition-[stroke-dasharray] duration-700" />
      </svg>
      <span className="text-xl">{emoji}</span>
    </span>
  )
}

function GoalDialog({ open, onOpenChange, goal }: { open: boolean; onOpenChange: (o: boolean) => void; goal?: Goal }) {
  const create = useCreateGoal()
  const update = useUpdateGoal()
  const { data: accounts = [] } = useAccounts()
  const [name, setName] = useState('')
  const [emoji, setEmoji] = useState(EMOJIS[0]!)
  const [target, setTarget] = useState('')
  const [date, setDate] = useState('')
  const [track, setTrack] = useState<'manual' | 'account'>('manual')
  const [accountId, setAccountId] = useState('')
  const [error, setError] = useState<string>()

  useEffect(() => {
    if (!open) return
    setName(goal?.name ?? '')
    setEmoji(goal?.emoji ?? EMOJIS[0]!)
    setTarget(goal ? minorToInput(goal.target, 'INR') : '')
    setDate(goal?.targetDate ?? '')
    setTrack(goal?.accountId ? 'account' : 'manual')
    setAccountId(goal?.accountId ?? '')
    setError(undefined)
  }, [open, goal?.id]) // eslint-disable-line react-hooks/exhaustive-deps

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(undefined)
    const amount = parseMoney(target)
    if (!name.trim()) return setError('Give it a name, like "Goa trip".')
    if (!amount || amount <= 0) return setError('Enter the amount you want to save, like 50,000.')
    if (track === 'account' && !accountId) return setError('Choose the account you save it in.')
    const fields = { name: name.trim(), emoji, target: amount, targetDate: date || null, accountId: track === 'account' ? accountId : null }
    try {
      if (goal) await update.mutateAsync({ id: goal.id, version: goal.version, fields })
      else await create.mutateAsync(fields)
      toast.success(goal ? 'Saved' : `${fields.name} added`)
      onOpenChange(false)
    } catch (err) {
      setError(err instanceof ApiError && err.code === 'plan-limit' ? 'The Free plan has one active goal. Archive it first, or try Premium for unlimited goals.'
        : err instanceof ApiError ? err.message : 'Could not save. Try again.')
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={goal ? `Edit ${goal.name}` : 'New goal'}>
      <form onSubmit={submit} className="flex flex-col gap-4 p-pad" noValidate>
        <Field label="What for" htmlFor="goal-name"><Input id="goal-name" autoFocus maxLength={60} placeholder="Goa trip" value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <fieldset>
          <legend className="mb-1.5 text-sm font-medium">Picture</legend>
          <div className="flex flex-wrap gap-1">
            {EMOJIS.map((x) => (
              <label key={x} className="flex size-10 cursor-pointer items-center justify-center rounded-control text-lg hover:bg-surface-2 has-[:checked]:bg-accent-soft has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-ring">
                <input type="radio" name="goal-emoji" value={x} checked={emoji === x} onChange={() => setEmoji(x)} className="sr-only" aria-label={x} />{x}
              </label>
            ))}
          </div>
        </fieldset>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Amount" htmlFor="goal-target"><Input id="goal-target" inputMode="decimal" className="tabular" placeholder="50,000" value={target} onChange={(e) => setTarget(e.target.value)} /></Field>
          <Field label="By (optional)" htmlFor="goal-date"><Input id="goal-date" type="date" value={date} onChange={(e) => setDate(e.target.value)} /></Field>
        </div>
        <div className="flex flex-col gap-2">
          <span className="text-sm font-medium">Count what's saved from</span>
          <Segmented label="Count what's saved from" value={track} onChange={setTrack} className="self-start"
            options={[{ value: 'manual', label: 'Money I add here' }, { value: 'account', label: 'An account' }]} />
          {track === 'account' && (
            <SearchSelect aria-label="Account" value={accountId} onChange={setAccountId} options={accountOptions(accounts)} placeholder="The account you save it in" />
          )}
          <p className="text-xs text-subtle">{track === 'account' ? 'Its balance is what you have saved: an RD or a savings account kept for this.' : 'Add money to the goal whenever you put some aside.'}</p>
        </div>
        {error && <p className="rounded-control bg-critical/10 px-3 py-2 text-sm text-critical" role="alert">{error}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button type="submit" variant="primary" disabled={create.isPending || update.isPending}>{goal ? 'Save' : 'Add goal'}</Button>
        </div>
      </form>
    </Dialog>
  )
}

function AddMoneyDialog({ goal, onClose, money }: { goal: Goal | null; onClose: () => void; money: (m: number) => string }) {
  const contribute = useContribute()
  const removeOne = useDeleteContribution()
  const { data: history = [] } = useGoalContributions(goal?.id)
  const [amount, setAmount] = useState('')
  const [note, setNote] = useState('')
  const [out, setOut] = useState(false)
  const [error, setError] = useState<string>()
  useEffect(() => { if (goal) { setAmount(goal.perMonth ? minorToInput(goal.perMonth, 'INR') : ''); setNote(''); setOut(false); setError(undefined) } }, [goal])

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!goal) return
    const value = parseMoney(amount)
    if (!value || value <= 0) return setError('Enter an amount, like 5,000.')
    try {
      const after = await contribute.mutateAsync({ id: goal.id, amount: out ? -value : value, note: note.trim() || undefined })
      toast.success(after.status === 'REACHED' ? `🎉 ${goal.name} reached!` : `${money(value)} ${out ? 'taken out of' : 'added to'} ${goal.name}`)
      onClose()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not save. Try again.')
    }
  }

  return (
    <Dialog open={goal !== null} onOpenChange={(o) => !o && onClose()} title={goal ? `Add money to ${goal.name}` : 'Add money'}>
      <form onSubmit={submit} className="flex flex-col gap-4 p-pad" noValidate>
        <Segmented label="Put in or take out" value={out ? 'out' : 'in'} onChange={(v) => setOut(v === 'out')} className="self-start"
          options={[{ value: 'in', label: 'Put in' }, { value: 'out', label: 'Take out' }]} />
        <Field label="Amount" htmlFor="goal-add" hint={goal?.perMonth ? `This month's share: ${money(goal.perMonth)}` : undefined}>
          <Input id="goal-add" autoFocus inputMode="decimal" className="tabular" value={amount} onChange={(e) => setAmount(e.target.value)} />
        </Field>
        <Field label="Note (optional)" htmlFor="goal-note"><Input id="goal-note" maxLength={120} placeholder="Diwali bonus" value={note} onChange={(e) => setNote(e.target.value)} /></Field>
        {error && <p className="rounded-control bg-critical/10 px-3 py-2 text-sm text-critical" role="alert">{error}</p>}
        {history.length > 0 && (
          <details className="text-sm">
            <summary className="cursor-pointer text-muted">History ({history.length})</summary>
            <ul className="mt-2 divide-y divide-border rounded-control border border-border">
              {history.map((h) => (
                <li key={h.id} className="flex items-center gap-2 px-3 py-1.5 text-xs">
                  <span className="text-subtle">{h.occurredOn}</span><span className="flex-1 truncate">{h.note}</span>
                  <span className={cn('tabular', h.amount < 0 && 'text-critical')}>{h.amount < 0 ? '−' : '+'}{money(Math.abs(h.amount))}</span>
                  <button type="button" aria-label="Remove this entry" className="text-subtle hover:text-critical" onClick={() => goal && removeOne.mutate({ id: goal.id, contributionId: h.id })}><Trash2 className="size-3.5" /></button>
                </li>
              ))}
            </ul>
          </details>
        )}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="primary" disabled={contribute.isPending}>{out ? 'Take out' : 'Add'}</Button>
        </div>
      </form>
    </Dialog>
  )
}
