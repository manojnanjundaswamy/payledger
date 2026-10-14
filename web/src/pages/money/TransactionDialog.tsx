import { uuid7 } from '@/lib/uuid'
import { SearchSelect } from '@/components/ui/search-select'
import { Link } from '@tanstack/react-router'
import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input, Segmented } from '@/components/ui/controls'
import { Dialog } from '@/components/ui/overlay'
import { ApiError } from '@/lib/api'
import { minorToInput, parseMoney } from '@/lib/money'
import {
  useAccounts, useCategories, useCreateTransaction, useDeleteTransaction, useUpdateTransaction,
  type Transaction, type TransactionFields, type TxnKind,
} from '@/state/money'
import { Field, TXN_KINDS, today, accountOptions, categoryOptions } from './parts'

interface Draft {
  kind: TxnKind
  amount: string
  accountId: string
  toAccountId: string
  categoryId: string
  occurredOn: string
  description: string
  note: string
}

function draftOf(t: Transaction | undefined, kind: TxnKind, accountId: string, date?: string): Draft {
  return t
    ? {
        kind: t.kind, amount: minorToInput(t.amount, t.currency), accountId: t.accountId, toAccountId: t.toAccountId ?? '',
        categoryId: t.categoryId ?? '', occurredOn: t.occurredOn, description: t.description, note: t.note ?? '',
      }
    : { kind, amount: '', accountId, toAccountId: '', categoryId: '', occurredOn: date ?? today(), description: '', note: '' }
}

/**
 * Add or edit an expense, income or transfer. Amount first: most entries are small UPI payments,
 * so the fastest path is amount, category, Enter.
 */
export function TransactionDialog({ open, onOpenChange, transaction, initialKind = 'EXPENSE', initialAccountId, initialDate }: {
  open: boolean
  onOpenChange: (open: boolean) => void
  transaction?: Transaction
  initialKind?: TxnKind
  initialAccountId?: string
  /** A new one's date (a day clicked on the calendar). */
  initialDate?: string
}) {
  const { data: accounts = [] } = useAccounts()
  const { data: categories = [] } = useCategories()
  const create = useCreateTransaction()
  const update = useUpdateTransaction()
  const remove = useDeleteTransaction()

  const defaultAccount = initialAccountId ?? accounts[0]?.id ?? ''
  const [draft, setDraft] = useState<Draft>(() => draftOf(transaction, initialKind, defaultAccount, initialDate))
  // One id per new transaction: resubmitting after an error or a double tap can't add it twice.
  const [clientId, setClientId] = useState(() => uuid7())
  const [error, setError] = useState<string>()

  useEffect(() => {
    if (open) {
      setDraft(draftOf(transaction, initialKind, defaultAccount, initialDate))
      setClientId(uuid7())
      setError(undefined)
    }
    // Reset only when the dialog opens or switches transaction, not on every accounts refetch.
  }, [open, transaction?.id, initialKind])

  // Opened before the accounts arrived (a link straight to ?add=): take the default once they do.
  useEffect(() => {
    if (open && defaultAccount) setDraft((d) => (d.accountId ? d : { ...d, accountId: defaultAccount }))
  }, [open, defaultAccount])

  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => setDraft((d) => ({ ...d, [key]: value }))
  const account = accounts.find((a) => a.id === draft.accountId)
  const currency = account?.currency ?? 'INR'
  const choices = useMemo(
    () => categories.filter((c) => c.kind === draft.kind && (!c.hidden || c.id === transaction?.categoryId)),
    [categories, draft.kind, transaction?.categoryId],
  )
  const amount = parseMoney(draft.amount, currency)
  const pending = create.isPending || update.isPending || remove.isPending

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(undefined)
    if (!amount) return setError('Enter an amount, like 486 or 1,200.50.')
    if (!draft.accountId) return setError('Add an account first.')
    const transfer = draft.kind === 'TRANSFER'
    if (transfer && !draft.toAccountId) return setError('Choose where the money went.')
    if (!transfer && !draft.categoryId) return setError('Choose a category.')
    const fields: TransactionFields = {
      kind: draft.kind,
      amount,
      accountId: draft.accountId,
      toAccountId: transfer ? draft.toAccountId : null,
      categoryId: transfer ? null : draft.categoryId,
      occurredOn: draft.occurredOn,
      description: draft.description.trim() || defaultDescription(draft, categories, accounts),
      note: draft.note.trim() || null,
    }
    try {
      if (transaction) {
        await update.mutateAsync({ id: transaction.id, version: transaction.version, fields })
        toast.success('Saved')
      } else {
        await create.mutateAsync({ id: clientId, ...fields })
        toast.success(`${TXN_KINDS[draft.kind].label} added`)
      }
      onOpenChange(false)
    } catch (err) {
      setError(err instanceof ApiError && err.code === 'version-conflict'
        ? 'This was changed in another tab or by someone else. Close and open it again.'
        : err instanceof Error ? err.message : 'Could not save. Try again.')
    }
  }

  async function onDelete() {
    if (!transaction) return
    try {
      await remove.mutateAsync({ id: transaction.id, version: transaction.version })
      toast('Deleted', { description: transaction.description })
      onOpenChange(false)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not delete. Try again.')
    }
  }

  const title = transaction ? `Edit ${TXN_KINDS[draft.kind].label.toLowerCase()}` : `Add ${TXN_KINDS[draft.kind].label.toLowerCase()}`
  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={title}>
      <form onSubmit={submit} className="flex flex-col gap-4 p-pad" noValidate>
        <Segmented
          label="Kind"
          value={draft.kind}
          onChange={(kind) => setDraft((d) => ({ ...d, kind, categoryId: '' }))}
          options={(Object.keys(TXN_KINDS) as TxnKind[]).map((k) => {
            const Icon = TXN_KINDS[k].icon
            return { value: k, label: <><Icon />{TXN_KINDS[k].label}</> }
          })}
          className="w-full"
        />
        <Field label="Amount" htmlFor="txn-amount">
          <Input id="txn-amount" inputMode="decimal" autoFocus autoComplete="off" placeholder="0" value={draft.amount}
            onChange={(e) => set('amount', e.target.value)} className="h-12 text-xl font-semibold tabular" />
        </Field>
        {draft.kind === 'TRANSFER' ? (
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="From" htmlFor="txn-from">
              <SearchSelect id="txn-from" value={draft.accountId} onChange={(v) => set('accountId', v)} options={accountOptions(accounts)} />
            </Field>
            <Field label="To" htmlFor="txn-to">
              <SearchSelect id="txn-to" value={draft.toAccountId} onChange={(v) => set('toAccountId', v)} options={accountOptions(accounts.filter((a) => a.id !== draft.accountId))} />
            </Field>
          </div>
        ) : (
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Category" htmlFor="txn-category">
              <SearchSelect id="txn-category" value={draft.categoryId} onChange={(v) => set('categoryId', v)} options={categoryOptions(choices, categories)} />
            </Field>
            <Field label={draft.kind === 'INCOME' ? 'Into' : 'Paid from'} htmlFor="txn-account">
              <SearchSelect id="txn-account" value={draft.accountId} onChange={(v) => set('accountId', v)} options={accountOptions(accounts)} />
            </Field>
          </div>
        )}
        <div className="grid gap-4 sm:grid-cols-[1fr_auto]">
          <Field label="Description" htmlFor="txn-description" hint="Optional: a merchant or a few words">
            <Input id="txn-description" maxLength={120} placeholder="Swiggy, BigBasket, rent…" value={draft.description}
              onChange={(e) => set('description', e.target.value)} />
          </Field>
          <Field label="Date" htmlFor="txn-date">
            <Input id="txn-date" type="date" required value={draft.occurredOn} onChange={(e) => set('occurredOn', e.target.value)} />
          </Field>
        </div>
        <Field label="Note" htmlFor="txn-note">
          <Input id="txn-note" maxLength={1000} value={draft.note} onChange={(e) => set('note', e.target.value)} />
        </Field>
        {error && <p className="rounded-control bg-critical/10 px-3 py-2 text-sm text-critical" role="alert">{error}</p>}
        {transaction && <Related transaction={transaction} categories={categories} accounts={accounts} onGo={() => onOpenChange(false)} />}
        <div className="flex items-center gap-2">
          {transaction && (
            <Button variant="ghost" onClick={onDelete} disabled={pending} className="text-critical">
              <Trash2 />Delete
            </Button>
          )}
          <span className="flex-1" />
          <Button variant="ghost" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button type="submit" variant="primary" disabled={pending}>{transaction ? 'Save' : 'Add'}</Button>
        </div>
      </form>
    </Dialog>
  )
}

/** Where this transaction belongs: everything else in its category or account, and its month's budget. */
function Related({ transaction: t, categories, accounts, onGo }: {
  transaction: Transaction
  categories: { id: string; name: string }[]
  accounts: { id: string; name: string }[]
  onGo: () => void
}) {
  const category = categories.find((c) => c.id === t.categoryId)
  const account = accounts.find((a) => a.id === t.accountId)
  const month = t.occurredOn.slice(0, 7)
  const link = 'rounded-full border border-border px-2.5 py-1 hover:border-border-strong hover:bg-surface-2'
  return (
    <nav aria-label="Related" className="flex flex-wrap items-center gap-1.5 text-xs text-muted">
      <span>See:</span>
      {category && <Link to="/transactions" search={{ category: category.id }} onClick={onGo} className={link}>All {category.name}</Link>}
      {account && <Link to="/transactions" search={{ account: account.id }} onClick={onGo} className={link}>All from {account.name}</Link>}
      {t.kind === 'EXPENSE' && <Link to="/budgets" search={{ month }} onClick={onGo} className={link}>Budget for {new Date(`${month}-01T00:00:00`).toLocaleDateString('en-IN', { month: 'long' })}</Link>}
    </nav>
  )
}

/** Nobody wants to type "Groceries" twice: an empty description falls back to the category or the accounts. */
function defaultDescription(d: Draft, categories: { id: string; name: string }[], accounts: { id: string; name: string }[]): string {
  if (d.kind === 'TRANSFER') {
    const to = accounts.find((a) => a.id === d.toAccountId)?.name
    return to ? `To ${to}` : 'Transfer'
  }
  return categories.find((c) => c.id === d.categoryId)?.name ?? TXN_KINDS[d.kind].label
}
