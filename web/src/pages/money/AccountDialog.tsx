import { useEffect, useState, type FormEvent } from 'react'
import { SearchSelect } from '@/components/ui/search-select'
import { MoneyInput } from '@/components/ui/money-input'
import { Archive, ArchiveRestore } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/controls'
import { Dialog } from '@/components/ui/overlay'
import { allCurrencies, currencySymbol, minorToInput, moneyDefaults, parseMoney } from '@/lib/money'
import { useCreateAccount, useUpdateAccount, type Account, type AccountKind } from '@/state/money'
import { ACCOUNT_KINDS, Field, today, NEW_ACCOUNT_KINDS } from './parts'

/** Add an account, or rename it, change its opening balance, archive it. */
export function AccountDialog({ open, onOpenChange, account, initialName, space, onCreated }: {
  open: boolean
  onOpenChange: (open: boolean) => void
  account?: Account
  /** "Add new" from a dropdown: what was typed there. */
  initialName?: string
  /** Where a new account goes, if not the space on screen (personal-only pickers). */
  space?: string
  /** A new account was made: the dropdown that asked for it selects it. */
  onCreated?: (account: Account) => void
}) {
  const create = useCreateAccount(space)
  const update = useUpdateAccount()
  const [kind, setKind] = useState<AccountKind>('BANK')
  const [name, setName] = useState('')
  const [opening, setOpening] = useState('')
  const [openingDate, setOpeningDate] = useState(today())
  // New accounts start in the space's currency; a foreign account (a USD card, an AED salary account) picks its own.
  const [newCurrency, setNewCurrency] = useState(moneyDefaults().currency)
  const currency = account?.currency ?? newCurrency
  const [error, setError] = useState<string>()

  useEffect(() => {
    if (!open) return
    setKind(account?.kind ?? 'BANK')
    setName(account?.name ?? initialName ?? '')
    setOpening(account ? minorToInput(account.openingBalance, account.currency) : '')
    setOpeningDate(account?.openingDate ?? today())
    setNewCurrency(moneyDefaults().currency)
    setError(undefined)
  }, [open, account])

  const card = kind === 'CARD'

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(undefined)
    if (!name.trim()) return setError('Give the account a name, like "HDFC savings".')
    const openingBalance = opening.trim() === '' ? 0 : parseMoney(opening, currency)
    if (openingBalance === null) return setError('Enter the balance as a number, like 25,000 or 1200.50.')
    try {
      if (account) {
        await update.mutateAsync({ id: account.id, name: name.trim(), openingBalance, openingDate })
        toast.success('Saved')
      } else {
        const made = await create.mutateAsync({ kind, name: name.trim(), openingBalance, openingDate, currency })
        toast.success(`${name.trim()} added`)
        onCreated?.(made)
      }
      onOpenChange(false)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not save. Try again.')
    }
  }

  async function toggleArchived() {
    if (!account) return
    await update.mutateAsync({ id: account.id, archived: !account.archived })
    toast(account.archived ? `${account.name} is back` : `${account.name} archived`,
      { description: account.archived ? undefined : 'Its history stays; it no longer takes new transactions.' })
    onOpenChange(false)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={account ? `Edit ${account.name}` : 'Add an account'}>
      <form onSubmit={submit} className="flex flex-col gap-4 p-pad" noValidate>
        {!account && (
          <fieldset className="grid grid-cols-2 gap-2">
            <legend className="mb-1.5 text-sm font-medium">Type</legend>
            {NEW_ACCOUNT_KINDS.map((k) => {
              const { label, icon: Icon, hint } = ACCOUNT_KINDS[k]
              return (
                <label key={k} className="flex cursor-pointer items-start gap-2.5 rounded-control border border-border p-3 has-[:checked]:border-accent has-[:checked]:bg-accent-soft">
                  <input type="radio" name="account-kind" value={k} checked={kind === k} onChange={() => setKind(k)} className="sr-only" />
                  <Icon className="mt-0.5 size-4 shrink-0 text-muted" />
                  <span><span className="block text-sm font-medium">{label}</span><span className="block text-xs text-subtle">{hint}</span></span>
                </label>
              )
            })}
          </fieldset>
        )}
        <Field label="Name" htmlFor="account-name">
          <Input id="account-name" maxLength={60} autoFocus placeholder={card ? 'ICICI Amazon Pay' : 'HDFC savings'} value={name}
            onChange={(e) => setName(e.target.value)} />
        </Field>
        {!account && (
          <Field label="Currency" htmlFor="account-currency" hint={currency === moneyDefaults().currency ? 'Like the rest of your money.' : 'Kept in its own currency; totals in your main currency leave it out.'}>
            <SearchSelect id="account-currency" aria-label="Currency" value={newCurrency} onChange={setNewCurrency}
              options={allCurrencies().map((c) => ({ value: c.code, label: `${c.code} · ${c.name}`, icon: <span className="w-6 text-center text-xs font-semibold" aria-hidden>{currencySymbol(c.code)}</span> }))} />
          </Field>
        )}
        <div className="grid gap-4 sm:grid-cols-[1fr_auto]">
          <Field label={card ? 'Amount owed' : 'Balance'} htmlFor="account-opening"
            hint={card ? 'What you owe on the card on this date' : 'What is in it on this date'}>
            <MoneyInput currency={currency} id="account-opening" autoComplete="off" placeholder="0" value={opening}
              onChange={(e) => setOpening(e.target.value)} className="tabular" />
          </Field>
          <Field label="On" htmlFor="account-opening-date">
            <Input id="account-opening-date" type="date" value={openingDate} onChange={(e) => setOpeningDate(e.target.value)} />
          </Field>
        </div>
        {error && <p className="rounded-control bg-critical/10 px-3 py-2 text-sm text-critical" role="alert">{error}</p>}
        <div className="flex items-center gap-2">
          {account && (
            <Button variant="ghost" onClick={toggleArchived} disabled={update.isPending}>
              {account.archived ? <><ArchiveRestore />Unarchive</> : <><Archive />Archive</>}
            </Button>
          )}
          <span className="flex-1" />
          <Button variant="ghost" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button type="submit" variant="primary" disabled={create.isPending || update.isPending}>{account ? 'Save' : 'Add account'}</Button>
        </div>
      </form>
    </Dialog>
  )
}
