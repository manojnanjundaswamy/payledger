import { uuid7 } from '@/lib/uuid'
import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input, Segmented, Switch } from '@/components/ui/controls'
import { Dialog } from '@/components/ui/overlay'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/cn'
import { formatMoney, minorToInput, parseMoney } from '@/lib/money'
import { Field, Select, today } from '@/pages/money/parts'
import {
  previewSplit, useAddExpense, useDeleteExpense, useUpdateExpense,
  type GroupExpense, type GroupMember, type SplitType,
} from '@/state/groups'

const SPLITS: { value: SplitType; label: string }[] = [
  { value: 'EQUAL', label: 'Equally' }, { value: 'EXACT', label: 'Amounts' }, { value: 'PERCENT', label: '%' }, { value: 'SHARES', label: 'Shares' },
]

/** Add or edit a shared expense: who paid, and how it splits between whom. */
export function ExpenseDialog({ open, onOpenChange, groupId, members, you, expense, initialDate }: {
  open: boolean
  onOpenChange: (open: boolean) => void
  groupId: string
  members: GroupMember[]
  you: string
  expense?: GroupExpense
  /** A new expense's date (from a day on the calendar). */
  initialDate?: string
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

  const total = parseMoney(amount)
  const chosen = members.filter((m) => included[m.id])
  // What each value means for the split, in the units core-service expects.
  const parts = chosen.map((m) => {
    const raw = values[m.id] ?? ''
    // An empty shares box is one share, as its placeholder says.
    const value = split === 'EXACT' ? parseMoney(raw) ?? 0 : split === 'PERCENT' ? Math.round(Number(raw || 0) * 100) : Math.round(Number(raw || 1))
    return { memberId: m.id, value }
  })
  const preview = useMemo(() => (total ? previewSplit(total, split, parts) : null), [total, split, JSON.stringify(parts)])

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(undefined)
    if (!description.trim()) return setError('What was it for? Like "Dinner at Thalassa".')
    if (total === null || total <= 0) return setError('Enter the amount, like 2,400.')
    if (chosen.length === 0) return setError('Choose who it is for.')
    const paidBy = severalPayers
      ? members.map((m) => ({ memberId: m.id, value: parseMoney(paid[m.id] ?? '') ?? 0 })).filter((p) => p.value > 0)
      : [{ memberId: payer, value: total }]
    if (paidBy.reduce((s, p) => s + p.value, 0) !== total) return setError(`What each person paid must add up to ${formatMoney(total)}.`)
    if (!preview) return setError(split === 'PERCENT' ? 'The percentages must add up to 100.' : 'Check the split.')
    if (split === 'EXACT' && parts.reduce((s, p) => s + p.value, 0) !== total) return setError(`The amounts must add up to ${formatMoney(total)}.`)
    const fields = {
      description: description.trim(), amount: total, occurredOn: date, category: category.trim() || null, paidBy, split,
      shares: split === 'EQUAL' ? chosen.map((m) => ({ memberId: m.id })) : parts,
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
            <Input id="gexp-amount" inputMode="decimal" autoComplete="off" placeholder="2,400" value={amount} onChange={(e) => setAmount(e.target.value)} className="tabular" />
          </Field>
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Date" htmlFor="gexp-date"><Input id="gexp-date" type="date" value={date} onChange={(e) => setDate(e.target.value)} /></Field>
          <Field label="Label (optional)" htmlFor="gexp-category"><Input id="gexp-category" maxLength={40} placeholder="Food" value={category} onChange={(e) => setCategory(e.target.value)} /></Field>
        </div>

        <fieldset className="flex flex-col gap-2">
          <div className="flex items-center justify-between gap-2">
            <legend className="text-sm font-medium">Paid by</legend>
            <span className="flex items-center gap-2 text-xs text-muted">
              <Switch checked={severalPayers} onCheckedChange={setSeveralPayers} label="Several people paid" />Several people paid
            </span>
          </div>
          {!severalPayers ? (
            <Select aria-label="Paid by" value={payer} onChange={(e) => setPayer(e.target.value)}>
              {members.map((m) => <option key={m.id} value={m.id}>{m.you ? `${m.name} (you)` : m.name}</option>)}
            </Select>
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
        </fieldset>

        <fieldset className="flex flex-col gap-2">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <legend className="text-sm font-medium">Split</legend>
            <Segmented label="Split" value={split} onChange={setSplit} options={SPLITS} />
          </div>
          <ul className="flex flex-col divide-y divide-border rounded-control border border-border">
            {members.map((m) => (
              <li key={m.id} className="flex items-center gap-3 px-3 py-2 text-sm">
                <input type="checkbox" id={`gexp-in-${m.id}`} checked={!!included[m.id]} className="size-4 accent-[var(--accent)]"
                  onChange={(e) => setIncluded({ ...included, [m.id]: e.target.checked })} />
                <label htmlFor={`gexp-in-${m.id}`} className="min-w-0 flex-1 truncate">{m.name}{m.you && ' (you)'}</label>
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
    </Dialog>
  )
}
