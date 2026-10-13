import { useEffect, useState, type FormEvent } from 'react'
import { Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input, Segmented } from '@/components/ui/controls'
import { Dialog } from '@/components/ui/overlay'
import { ApiError } from '@/lib/api'
import { minorToInput, parseMoney } from '@/lib/money'
import {
  categoryLabel, categoryTree, useAccounts, useCategories, useCreateCommitment, useDeleteCommitment, useUpdateCommitment,
  type Commitment, type CommitmentKind,
} from '@/state/money'
import { Field, Select, today } from './parts'

/** 0 is "only once": stored as a yearly bill whose last payment is its first. */
const ONCE = 0
const FREQUENCIES = [
  { months: ONCE, label: 'Only once' },
  { months: 1, label: 'Every month' },
  { months: 3, label: 'Every 3 months' },
  { months: 6, label: 'Every 6 months' },
  { months: 12, label: 'Every year' },
]

interface Draft {
  kind: CommitmentKind
  name: string
  amount: string
  accountId: string
  categoryId: string
  toAccountId: string
  everyMonths: number
  firstDue: string
  endsOn: string
}

/** Add or change a bill (EMI, rent, insurance, subscription) or a saving (SIP, RD) that repeats. */
export function CommitmentDialog({ open, onOpenChange, commitment }: {
  open: boolean
  onOpenChange: (open: boolean) => void
  commitment?: Commitment
}) {
  const { data: accounts = [] } = useAccounts()
  const { data: categories = [] } = useCategories()
  const create = useCreateCommitment()
  const update = useUpdateCommitment()
  const remove = useDeleteCommitment()
  const [d, setDraft] = useState<Draft>(empty())
  const [error, setError] = useState<string>()
  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => setDraft((x) => ({ ...x, [key]: value }))

  useEffect(() => {
    if (!open) return
    setDraft(commitment ? {
      kind: commitment.kind, name: commitment.name, amount: minorToInput(commitment.amount, commitment.currency),
      accountId: commitment.accountId, categoryId: commitment.categoryId ?? '', toAccountId: commitment.toAccountId ?? '',
      everyMonths: commitment.endsOn === commitment.firstDue ? ONCE : commitment.everyMonths,
      firstDue: commitment.firstDue, endsOn: commitment.endsOn === commitment.firstDue ? '' : commitment.endsOn ?? '',
    } : empty())
    setError(undefined)
  }, [open, commitment])

  // No account chosen yet: the first one (accounts may still have been loading when the dialog opened).
  const accountId = d.accountId || accounts[0]?.id || ''
  const bill = d.kind === 'BILL'
  const expenseCategories = categoryTree(categories.filter((c) => c.kind === 'EXPENSE' && (!c.hidden || c.id === commitment?.categoryId)))
    .flatMap(({ category, children }) => [category, ...children])

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(undefined)
    if (!d.name.trim()) return setError(bill ? 'Give it a name, like "Car EMI" or "Rent".' : 'Give it a name, like "SIP: index fund".')
    const amount = parseMoney(d.amount)
    if (amount === null || amount <= 0) return setError('Enter the amount, like 12,500.')
    if (!accountId) return setError('Add an account first: it is paid from one.')
    if (bill && !d.categoryId) return setError('Choose a category.')
    if (!bill && !d.toAccountId) return setError('Choose where the money goes.')
    const once = d.everyMonths === ONCE
    if (!once && d.endsOn && d.endsOn < d.firstDue) return setError('The last payment can’t be before the first.')
    const fields = {
      kind: d.kind, name: d.name.trim(), amount, accountId,
      categoryId: bill ? d.categoryId : null, toAccountId: bill ? null : d.toAccountId,
      everyMonths: once ? 12 : d.everyMonths, firstDue: d.firstDue, endsOn: once ? d.firstDue : d.endsOn || null,
    }
    try {
      if (commitment) {
        await update.mutateAsync({ id: commitment.id, version: commitment.version, fields })
        toast.success('Saved')
      } else {
        await create.mutateAsync(fields)
        toast.success(`${fields.name} added to your plan`)
      }
      onOpenChange(false)
    } catch (err) {
      if (err instanceof ApiError && err.code === 'version-conflict') return setError('It was changed somewhere else. Close and open it again.')
      setError(err instanceof Error ? err.message : 'Could not save. Try again.')
    }
  }

  async function deleteIt() {
    if (!commitment) return
    await remove.mutateAsync(commitment.id)
    toast(`${commitment.name} removed from your plan`, { description: 'Payments already made stay in your transactions.' })
    onOpenChange(false)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={commitment ? `Edit ${commitment.name}` : 'Add to your plan'}>
      <form onSubmit={submit} className="flex flex-col gap-4 p-pad" noValidate>
        {!commitment && (
          <Segmented label="Type" value={d.kind} onChange={(kind) => set('kind', kind)} className="self-start"
            options={[{ value: 'BILL', label: 'Bill or EMI' }, { value: 'SAVING', label: 'Saving or SIP' }]} />
        )}
        <div className="grid gap-4 sm:grid-cols-[1fr_10rem]">
          <Field label="Name" htmlFor="commitment-name">
            <Input id="commitment-name" maxLength={60} autoFocus placeholder={bill ? 'Car EMI' : 'SIP: index fund'} value={d.name}
              onChange={(e) => set('name', e.target.value)} />
          </Field>
          <Field label="Amount" htmlFor="commitment-amount">
            <Input id="commitment-amount" inputMode="decimal" autoComplete="off" placeholder="12,500" value={d.amount}
              onChange={(e) => set('amount', e.target.value)} className="tabular" />
          </Field>
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Paid from" htmlFor="commitment-account">
            <Select id="commitment-account" value={accountId} onChange={(e) => set('accountId', e.target.value)}>
              {accounts.length === 0 && <option value="">No accounts yet</option>}
              {accounts.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
            </Select>
          </Field>
          {bill ? (
            <Field label="Category" htmlFor="commitment-category">
              <Select id="commitment-category" value={d.categoryId} onChange={(e) => set('categoryId', e.target.value)}>
                <option value="">Choose…</option>
                {expenseCategories.map((c) => <option key={c.id} value={c.id}>{categoryLabel(c, categories)}</option>)}
              </Select>
            </Field>
          ) : (
            <Field label="Into" htmlFor="commitment-to">
              <Select id="commitment-to" value={d.toAccountId} onChange={(e) => set('toAccountId', e.target.value)}>
                <option value="">Choose…</option>
                {accounts.filter((a) => a.id !== accountId).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
              </Select>
            </Field>
          )}
        </div>
        <div className="grid gap-4 sm:grid-cols-3">
          <Field label="How often" htmlFor="commitment-every">
            <Select id="commitment-every" value={d.everyMonths} onChange={(e) => set('everyMonths', Number(e.target.value))}>
              {FREQUENCIES.map((f) => <option key={f.months} value={f.months}>{f.label}</option>)}
            </Select>
          </Field>
          <Field label={d.everyMonths === ONCE ? 'Due on' : commitment ? 'First due' : 'Next due'} htmlFor="commitment-first">
            <Input id="commitment-first" type="date" value={d.firstDue} onChange={(e) => set('firstDue', e.target.value)} />
          </Field>
          {d.everyMonths !== ONCE && (
            <Field label="Last payment" htmlFor="commitment-ends" hint="Optional, e.g. an EMI's end">
              <Input id="commitment-ends" type="date" value={d.endsOn} onChange={(e) => set('endsOn', e.target.value)} />
            </Field>
          )}
        </div>
        {error && <p className="rounded-control bg-critical/10 px-3 py-2 text-sm text-critical" role="alert">{error}</p>}
        <div className="flex items-center gap-2">
          {commitment && <Button variant="ghost" onClick={deleteIt} disabled={remove.isPending}><Trash2 />Remove</Button>}
          <span className="flex-1" />
          <Button variant="ghost" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button type="submit" variant="primary" disabled={create.isPending || update.isPending}>{commitment ? 'Save' : 'Add'}</Button>
        </div>
      </form>
    </Dialog>
  )
}

function empty(): Draft {
  return { kind: 'BILL', name: '', amount: '', accountId: '', categoryId: '', toAccountId: '', everyMonths: 1, firstDue: today(), endsOn: '' }
}
