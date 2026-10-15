import { uuid7 } from '@/lib/uuid'
import { MoneyInput } from '@/components/ui/money-input'
import { AccountPicker } from '@/pages/money/pickers'
import { SearchSelect } from '@/components/ui/search-select'
import { categoryTree, useAccounts, useCategories, useGroupBudget, usePersonalSpaceId } from '@/state/money'
import { CategoryIcon, accountOptions } from '@/pages/money/parts'
import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Trash2 } from 'lucide-react'
import { ExpenseComments } from './Comments'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input, Segmented, Switch } from '@/components/ui/controls'
import { Dialog } from '@/components/ui/overlay'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/cn'
import { formatMoney, minorToInput, parseMoney } from '@/lib/money'
import { Field, today } from '@/pages/money/parts'
import { useActivity, previewSplit, useAddExpense, useDeleteExpense, useUpdateExpense, type GroupExpense, type GroupMember, type SplitType } from '@/state/groups'

const SPLITS: { value: SplitType; label: string }[] = [
  { value: 'EQUAL', label: 'Equally' }, { value: 'EXACT', label: 'Amounts' }, { value: 'PERCENT', label: '%' }, { value: 'SHARES', label: 'Shares' },
]

/** Add or edit a shared expense: who paid, and how it splits between whom. */
export function ExpenseDialog({ open, onOpenChange, groupId, members, you, expense, initialDate, currency: groupCurrency }: {
  open: boolean
  onOpenChange: (open: boolean) => void
  groupId: string
  members: GroupMember[]
  you: string
  expense?: GroupExpense
  /** A new expense's date (from a day on the calendar). */
  initialDate?: string
  /** The group's currency: amounts here are in it. */
  currency?: string
}) {
  const add = useAddExpense(groupId)
  const update = useUpdateExpense(groupId)
  const remove = useDeleteExpense(groupId)
  const [clientId, setClientId] = useState(() => uuid7())
  const [description, setDescription] = useState('')
  const [amount, setAmount] = useState('')
  const [date, setDate] = useState(today())
  const [category, setCategory] = useState('')
  const [split, setSplit] = useState<SplitType>('EQUAL')
  const [included, setIncluded] = useState<Record<string, boolean>>({})
  const [values, setValues] = useState<Record<string, string>>({})
  const [severalPayers, setSeveralPayers] = useState(false)
  const [payer, setPayer] = useState(you)
  const [paid, setPaid] = useState<Record<string, string>>({})
  const [error, setError] = useState<string>()
  const [fromAccount, setFromAccount] = useState('')
  const { data: bookSettings } = useGroupBudget()
  const personalSpace = usePersonalSpaceId()
  const { data: myAccounts } = useAccounts(false, personalSpace)
  const payable = (myAccounts ?? []).filter((a) => a.kind !== 'OWED' && !a.archived)

  useEffect(() => {
    if (!open) return
    setClientId(uuid7())
    setError(undefined)
    if (expense) {
      setDescription(expense.description)
      setAmount(minorToInput(expense.amount, expense.currency))
      setDate(expense.occurredOn)
      setCategory(expense.category ?? '')
      setSplit(expense.split)
      setIncluded(Object.fromEntries(members.map((m) => [m.id, expense.parts.some((p) => p.memberId === m.id && (p.share > 0 || p.input !== null))])))
      setValues(Object.fromEntries(expense.parts.filter((p) => p.input !== null).map((p) => [p.memberId,
        expense.split === 'EXACT' ? minorToInput(p.input!, expense.currency) : expense.split === 'PERCENT' ? String(p.input! / 100) : String(p.input)])))
      const payers = expense.parts.filter((p) => p.paid > 0)
      setSeveralPayers(payers.length > 1)
      setPayer(payers[0]?.memberId ?? you)
      setPaid(Object.fromEntries(payers.map((p) => [p.memberId, minorToInput(p.paid, expense.currency)])))
    } else {
      setDescription('')
      setAmount('')
      setDate(initialDate ?? today())
      setCategory('')
      setSplit('EQUAL')
      setIncluded(Object.fromEntries(members.map((m) => [m.id, true])))
      setValues({})
      setSeveralPayers(false)
      setPayer(you)
      setPaid({})
    }
  }, [open, expense, members, you])

  const total = parseMoney(amount, groupCurrency)
  // The category: one of your own spending categories, or a label this group already uses, or a new one. It's
  // stored by name, so each member's share lands in their own category of that name (or Other).
  const { data: myCategories = [] } = useCategories(personalSpace)
  const { data: activity } = useActivity(groupId)
  const labelOptions = useMemo(() => {
    const mine = myCategories.filter((c) => c.kind === 'EXPENSE' && !c.hidden)
    const used = [...new Set((activity?.expenses ?? []).map((e) => e.category).filter((c): c is string => !!c))]
      .filter((l) => !mine.some((c) => c.name.toLowerCase() === l.toLowerCase()))
    return [
      { value: '', label: 'No category' },
      ...used.map((l) => ({ value: l, label: l, group: 'Used in this group' })),
      ...categoryTree(mine).flatMap(({ category, children }) => [category, ...children]).map((c) => ({
        value: c.name, label: c.name, group: 'Your categories', indent: !!c.parentId, icon: <CategoryIcon category={c} className="size-5 bg-transparent" />,
      })),
    ]
  }, [myCategories, activity])
  const chosen = members.filter((m) => included[m.id])
  // What each value means for the split, in the units core-service expects.
  const parts = chosen.map((m) => {
    const raw = values[m.id] ?? ''
    // An empty shares box is one share, as its placeholder says.
    const value = split === 'EXACT' ? parseMoney(raw, groupCurrency) ?? 0 : split === 'PERCENT' ? Math.round(Number(raw || 0) * 100) : Math.round(Number(raw || 1))
    return { memberId: m.id, value }
  })
  const preview = useMemo(() => (total ? previewSplit(total, split, parts) : null), [total, split, JSON.stringify(parts)])
  // What each person put in, as typed: the amount is split by "Split", and each person's result is their share less this.
  const paidOf = (id: string) => severalPayers ? parseMoney(paid[id] ?? '', groupCurrency) ?? 0 : id === payer ? total ?? 0 : 0
  const paidTotal = severalPayers ? members.reduce((s, m) => s + paidOf(m.id), 0) : total ?? 0
  const unpaid = (total ?? 0) - paidTotal
  /** Turning on several payers starts from who was chosen paying it all. */
  function chooseSeveral(on: boolean) {
    if (on && total) setPaid({ [payer]: minorToInput(total, groupCurrency) })
    setSeveralPayers(on)
  }
  /** Each person's share is what they paid: nobody owes anybody for this one. */
  function splitAsPaid() {
    setSplit('EXACT')
    setIncluded(Object.fromEntries(members.map((m) => [m.id, paidOf(m.id) > 0])))
    setValues(Object.fromEntries(members.filter((m) => paidOf(m.id) > 0).map((m) => [m.id, minorToInput(paidOf(m.id), groupCurrency)])))
  }

  const youPaid = severalPayers ? (parseMoney(paid[you] ?? '', groupCurrency) ?? 0) > 0 : payer === you

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(undefined)
    if (!description.trim()) return setError('What was it for? Like "Dinner at Thalassa".')
    if (total === null || total <= 0) return setError('Enter the amount, like 2,400.')
    if (chosen.length === 0) return setError('Choose who it is for.')
    const paidBy = severalPayers
      ? members.map((m) => ({ memberId: m.id, value: parseMoney(paid[m.id] ?? '', groupCurrency) ?? 0 })).filter((p) => p.value > 0)
      : [{ memberId: payer, value: total }]
    if (paidBy.reduce((s, p) => s + p.value, 0) !== total) return setError(`What each person paid must add up to ${formatMoney(total)}.`)
    if (!preview) return setError(split === 'PERCENT' ? 'The percentages must add up to 100.' : 'Check the split.')
    if (split === 'EXACT' && parts.reduce((s, p) => s + p.value, 0) !== total) return setError(`The amounts must add up to ${formatMoney(total)}.`)
    const fields = {
      description: description.trim(), amount: total, occurredOn: date, category: category.trim() || null, paidBy, split,
      shares: split === 'EQUAL' ? chosen.map((m) => ({ memberId: m.id })) : parts,
      paidFromAccountId: youPaid && fromAccount ? fromAccount : null,
      // A loan stays a loan when edited here.
      loan: expense?.loan ?? false, dueOn: expense?.dueOn ?? null,
    }
    try {
      if (expense) await update.mutateAsync({ expenseId: expense.id, version: expense.version, fields })
      else await add.mutateAsync({ id: clientId, ...fields })
      toast.success(expense ? 'Saved' : `${fields.description} added`)
      onOpenChange(false)
    } catch (err) {
      setError(err instanceof ApiError && err.code === 'version-conflict' ? 'Someone changed it meanwhile. Close and open it again.'
        : err instanceof ApiError ? err.message : 'Could not save. Try again.')
    }
  }

  async function deleteIt() {
    if (!expense) return
    await remove.mutateAsync({ expenseId: expense.id, version: expense.version })
    toast(`${expense.description} deleted`)
    onOpenChange(false)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={expense ? 'Edit expense' : 'Add a shared expense'}>
      <form onSubmit={submit} className="flex flex-col gap-4 p-pad" noValidate>
        <div className="grid gap-4 sm:grid-cols-[1fr_9rem]">
          <Field label="What for" htmlFor="gexp-description">
            <Input id="gexp-description" autoFocus maxLength={120} placeholder="Dinner at Thalassa" value={description} onChange={(e) => setDescription(e.target.value)} />
          </Field>
          <Field label="Amount" htmlFor="gexp-amount">
            <MoneyInput currency={groupCurrency} id="gexp-amount" autoComplete="off" placeholder="2,400" value={amount} onChange={(e) => setAmount(e.target.value)} className="tabular" />
          </Field>
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Date" htmlFor="gexp-date"><Input id="gexp-date" type="date" value={date} onChange={(e) => setDate(e.target.value)} /></Field>
          <Field label="Category" htmlFor="gexp-category">
            <SearchSelect id="gexp-category" value={category} onChange={setCategory} options={labelOptions} allowCustom customLabel="Add"
              placeholder="Food, Travel, Stay…" />
          </Field>
        </div>

        {youPaid && bookSettings?.enabled && payable.length > 0 && (
          <Field label="You paid from" htmlFor="gexp-from" hint="Your share counts in your budget; the rest waits in Friends & groups until they pay you back.">
            <AccountPicker space={personalSpace} id="gexp-from" value={fromAccount} onChange={setFromAccount}
              options={[{ value: '', label: 'My usual account' }, ...accountOptions(payable)]} />
          </Field>
        )}

        <fieldset className="flex flex-col gap-2">
          <div className="flex items-center justify-between gap-2">
            <legend className="text-sm font-medium">Paid by</legend>
            <span className="flex items-center gap-2 text-xs text-muted">
              <Switch checked={severalPayers} onCheckedChange={chooseSeveral} label="Several people paid" />Several people paid
            </span>
          </div>
          {!severalPayers ? (
            <SearchSelect aria-label="Paid by" value={payer} onChange={setPayer}
              options={members.map((m) => ({ value: m.id, label: m.you ? `${m.name} (you)` : m.name }))} />
          ) : (
            <div className="grid gap-2 sm:grid-cols-2">
              {members.map((m) => (
                <label key={m.id} className="flex items-center gap-2 text-sm">
                  <span className="w-24 truncate">{m.name}</span>
                  <Input aria-label={`${m.name} paid`} inputMode="decimal" placeholder="0" value={paid[m.id] ?? ''}
                    onChange={(e) => setPaid({ ...paid, [m.id]: e.target.value })} className="tabular" />
                </label>
              ))}
            </div>
          )}
          {severalPayers && (
            <div className={cn('flex flex-wrap items-center gap-x-3 gap-y-1 rounded-control px-3 py-2 text-xs', unpaid === 0 ? 'bg-good/10 text-good-text' : 'bg-critical/10 text-critical')}
              data-testid="paid-total" role="status">
              <span className="tabular">
                {unpaid === 0 ? `✓ Paid ${formatMoney(paidTotal)}: adds up` : total ? `Paid ${formatMoney(paidTotal)} of ${formatMoney(total)}: ${unpaid > 0 ? `${formatMoney(unpaid)} still to add` : `${formatMoney(-unpaid)} too much`}` : `Paid ${formatMoney(paidTotal)}`}
              </span>
              {unpaid !== 0 && paidTotal > 0 && (
                <button type="button" className="underline underline-offset-2" onClick={() => setAmount(minorToInput(paidTotal, groupCurrency))}>
                  Make the amount {formatMoney(paidTotal)}
                </button>
              )}
              {unpaid > 0 && (
                <button type="button" className="underline underline-offset-2" onClick={() => setPaid({ ...paid, [you]: minorToInput(paidOf(you) + unpaid, groupCurrency) })}>
                  I paid the rest
                </button>
              )}
            </div>
          )}
        </fieldset>

        <fieldset className="flex flex-col gap-2">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <legend className="text-sm font-medium">Split: who it was for</legend>
            <Segmented label="Split" value={split} onChange={setSplit} options={SPLITS} />
          </div>
          {severalPayers && paidTotal > 0 && (
            <p className="text-xs text-muted">
              What each person paid doesn't change their share.{' '}
              <button type="button" className="text-fg underline underline-offset-2" onClick={splitAsPaid}>Split as paid</button>{' '}
              if each paid for their own part.
            </p>
          )}
          <ul className="flex flex-col divide-y divide-border rounded-control border border-border">
            {members.map((m) => (
              <li key={m.id} className="flex items-center gap-3 px-3 py-2 text-sm">
                <input type="checkbox" id={`gexp-in-${m.id}`} checked={!!included[m.id]} className="size-4 accent-[var(--accent)]"
                  onChange={(e) => setIncluded({ ...included, [m.id]: e.target.checked })} />
                <label htmlFor={`gexp-in-${m.id}`} className="min-w-0 flex-1">
                  <span className="block truncate">{m.name}{m.you && ' (you)'}</span>
                  <Outcome paid={paidOf(m.id)} share={included[m.id] && preview ? preview[m.id] ?? 0 : 0} show={!!preview && !!total} you={m.you} />
                </label>
                {split !== 'EQUAL' && included[m.id] && (
                  <Input aria-label={`${m.name}'s ${split === 'EXACT' ? 'amount' : split === 'PERCENT' ? 'percentage' : 'shares'}`}
                    inputMode="decimal" className="h-8 w-24 tabular" placeholder={split === 'PERCENT' ? '%' : split === 'SHARES' ? '1' : '0'}
                    value={values[m.id] ?? ''} onChange={(e) => setValues({ ...values, [m.id]: e.target.value })} />
                )}
                <span className={cn('w-20 shrink-0 text-right tabular', preview?.[m.id] ? 'text-fg' : 'text-subtle')} data-testid={`share-${m.name}`}>
                  {included[m.id] && preview ? formatMoney(preview[m.id] ?? 0) : '—'}
                </span>
              </li>
            ))}
          </ul>
        </fieldset>

        {error && <p className="rounded-control bg-critical/10 px-3 py-2 text-sm text-critical" role="alert">{error}</p>}
        <div className="flex items-center gap-2">
          {expense && <Button variant="ghost" onClick={deleteIt} disabled={remove.isPending}><Trash2 />Delete</Button>}
          <span className="flex-1" />
          <Button variant="ghost" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button type="submit" variant="primary" disabled={add.isPending || update.isPending}>{expense ? 'Save' : 'Add expense'}</Button>
        </div>
      </form>
      {/* Outside the form: a comment box isn't part of saving the expense. */}
      {expense && <div className="px-pad pb-pad"><ExpenseComments groupId={groupId} expenseId={expense.id} /></div>}
    </Dialog>
  )
}

/** A person's result from this expense: paid, and what that leaves them owing or getting back. */
function Outcome({ paid, share, show, you }: { paid: number; share: number; show: boolean; you: boolean }) {
  if (!show || (paid === 0 && share === 0)) return null
  const net = paid - share
  return (
    <span className="block text-xs text-subtle tabular" data-testid="outcome">
      {paid > 0 && `paid ${formatMoney(paid)} · `}
      {net === 0 ? 'even' : net > 0
        ? <span className="text-good-text">{you ? 'you get back' : 'gets back'} {formatMoney(net)}</span>
        : <span className="text-critical">{you ? 'you owe' : 'owes'} {formatMoney(-net)}</span>}
    </span>
  )
}
