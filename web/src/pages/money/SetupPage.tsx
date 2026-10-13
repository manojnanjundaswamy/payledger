import { useMemo, useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Check, ChevronLeft, ChevronRight, Plus, Sparkles, Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/controls'
import { api, ApiError } from '@/lib/api'
import { cn } from '@/lib/cn'
import { formatMoneyWhole, parseMoney } from '@/lib/money'
import { usePlan, useStartTrial } from '@/state/plan'
import { useSpaceId, type AccountKind } from '@/state/money'
import { useProfile } from '@/state/profile'
import { ACCOUNT_KINDS, Field, Select } from './parts'

interface AccountRow { kind: AccountKind; name: string; balance: string }
interface PaymentRow { key: string; on: boolean; kind: 'BILL' | 'SAVING'; name: string; amount: string; day: string; category: string; everyMonths: number; account: number }
interface BudgetRow { category: string; label: string; on: boolean; amount: string; share: number }

/** Common regular payments in India, with usual prices; people tick theirs and fix the amounts. */
const PAYMENTS: Omit<PaymentRow, 'on' | 'account'>[] = [
  { key: 'rent', kind: 'BILL', name: 'Rent', amount: '', day: '5', category: 'rent', everyMonths: 1 },
  { key: 'home-emi', kind: 'BILL', name: 'Home loan EMI', amount: '', day: '5', category: 'emi', everyMonths: 1 },
  { key: 'vehicle-emi', kind: 'BILL', name: 'Car or bike EMI', amount: '', day: '10', category: 'emi', everyMonths: 1 },
  { key: 'sip', kind: 'SAVING', name: 'Mutual fund SIP', amount: '5,000', day: '10', category: '', everyMonths: 1 },
  { key: 'electricity', kind: 'BILL', name: 'Electricity', amount: '1,500', day: '15', category: 'utilities', everyMonths: 1 },
  { key: 'broadband', kind: 'BILL', name: 'Broadband', amount: '799', day: '1', category: 'utilities', everyMonths: 1 },
  { key: 'mobile', kind: 'BILL', name: 'Mobile recharge', amount: '349', day: '1', category: 'utilities', everyMonths: 1 },
  { key: 'netflix', kind: 'BILL', name: 'Netflix', amount: '649', day: '1', category: 'subscriptions', everyMonths: 1 },
  { key: 'hotstar', kind: 'BILL', name: 'JioHotstar', amount: '299', day: '1', category: 'subscriptions', everyMonths: 1 },
  { key: 'spotify', kind: 'BILL', name: 'Spotify', amount: '119', day: '1', category: 'subscriptions', everyMonths: 1 },
  { key: 'youtube', kind: 'BILL', name: 'YouTube Premium', amount: '149', day: '1', category: 'subscriptions', everyMonths: 1 },
  { key: 'maid', kind: 'BILL', name: 'Household help', amount: '', day: '1', category: 'household-help', everyMonths: 1 },
  { key: 'insurance', kind: 'BILL', name: 'Health insurance', amount: '', day: '1', category: 'insurance', everyMonths: 12 },
]

/** Starting budgets as a share of take-home pay; every number is editable. */
const BUDGETS: Omit<BudgetRow, 'on' | 'amount'>[] = [
  { category: 'groceries', label: 'Groceries', share: 0.1 },
  { category: 'eating-out', label: 'Eating out', share: 0.05 },
  { category: 'transport', label: 'Fuel & transport', share: 0.05 },
  { category: 'shopping', label: 'Shopping', share: 0.05 },
  { category: 'health', label: 'Health', share: 0.03 },
]

const STEPS = ['Payday', 'Accounts', 'Regular payments', 'Budgets'] as const

/**
 * A useful budget in two minutes (docs/plan/onboarding-and-features.md): payday, where the money is, what goes out
 * every month, and a first budget. One request at the end creates it all, or nothing.
 */
export function SetupPage() {
  const navigate = useNavigate()
  const spaceId = useSpaceId()
  const locale = useProfile().data?.settings.locale ?? 'en-IN'
  const { data: plan } = usePlan()
  const trial = useStartTrial()
  const client = useQueryClient()
  const [step, setStep] = useState(0)
  const [payday, setPayday] = useState('1')
  const [income, setIncome] = useState('')
  const [accounts, setAccounts] = useState<AccountRow[]>([{ kind: 'BANK', name: 'Salary account', balance: '' }, { kind: 'CASH', name: 'Cash', balance: '' }])
  const [payments, setPayments] = useState<PaymentRow[]>(PAYMENTS.map((p) => ({ ...p, on: false, account: 0 })))
  const [budgets, setBudgets] = useState<BudgetRow[]>(BUDGETS.map((b) => ({ ...b, on: true, amount: '' })))
  const [error, setError] = useState<string>()

  const money = (m: number) => formatMoneyWhole(m, 'INR', locale)
  const takeHome = parseMoney(income)
  // Budgets follow the take-home pay until someone types their own number.
  const shownBudgets = useMemo(() => budgets.map((b) => ({
    ...b, amount: b.amount || (takeHome ? String(Math.round((takeHome * b.share) / 50_000) * 500) : ''),
  })), [budgets, takeHome])

  const limits = plan?.limits ?? null
  const big = (p: PaymentRow) => p.kind === 'BILL' && p.everyMonths >= 6
  const chosen = payments.filter((p) => p.on)
  const needsSavings = chosen.some((p) => p.kind === 'SAVING')
  const counts = {
    accounts: accounts.length + (needsSavings ? 1 : 0),
    bills: chosen.filter((p) => !big(p)).length,
    'big-costs': chosen.filter(big).length,
    budgets: shownBudgets.filter((b) => b.on).length,
  }
  const over = limits ? (Object.keys(counts) as (keyof typeof counts)[]).filter((k) => counts[k] > (limits[k] ?? Infinity)) : []

  const submit = useMutation({
    mutationFn: (body: unknown) => api(`/api/core/spaces/${spaceId}/setup`, { method: 'POST', json: body }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['money'] })
      void client.invalidateQueries({ queryKey: ['profile'] })
      void client.invalidateQueries({ queryKey: ['plan'] })
    },
  })

  async function finish() {
    setError(undefined)
    const accountsIn = accounts.filter((a) => a.name.trim()).map((a) => ({ kind: a.kind, name: a.name.trim(), openingBalance: parseMoney(a.balance) ?? 0 }))
    if (accountsIn.length === 0) return setError('Add at least one account: where your money is.')
    const paymentsIn = chosen.map((p) => ({
      kind: p.kind, name: p.name.trim(), amount: parseMoney(p.amount), category: p.kind === 'BILL' ? p.category : undefined,
      account: p.account, day: Number(p.day) || 1, everyMonths: p.everyMonths,
    }))
    const missing = paymentsIn.find((p) => !p.amount || p.amount <= 0 || !p.name)
    if (missing) { setStep(2); return setError(`Enter the amount for ${missing.name || 'each ticked payment'}.`) }
    const budgetsIn = shownBudgets.filter((b) => b.on).map((b) => ({ category: b.category, amount: parseMoney(b.amount) }))
      .filter((b): b is { category: string; amount: number } => !!b.amount && b.amount > 0)
    try {
      await submit.mutateAsync({ budgetStartDay: Number(payday), accounts: accountsIn, payments: paymentsIn, budgets: budgetsIn })
      toast.success('You’re set up', { description: 'Your plan, budgets and safe to spend are ready.' })
      void navigate({ to: '/dashboard' })
    } catch (err) {
      setError(err instanceof ApiError && err.code === 'already-set-up' ? 'You already have accounts. Add more from the Accounts and Plan pages.'
        : err instanceof ApiError && err.code === 'plan-limit' ? `${err.message}. Untick some, or start the free Premium trial.`
        : err instanceof ApiError ? err.message : 'Could not save. Try again.')
    }
  }

  const setPayment = (i: number, patch: Partial<PaymentRow>) => setPayments(payments.map((p, j) => (j === i ? { ...p, ...patch } : p)))

  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-5">
      <div>
        <h2 className="text-2xl font-semibold tracking-tight">Set up in two minutes</h2>
        <p className="text-sm text-muted">Answer a few questions and PayLedger builds your plan, budgets and safe to spend. You can change anything later.</p>
      </div>

      <ol className="flex gap-2" aria-label="Steps">
        {STEPS.map((s, i) => (
          <li key={s} className="flex-1">
            <button type="button" onClick={() => setStep(i)} aria-current={i === step ? 'step' : undefined}
              className={cn('w-full border-t-2 pt-2 text-left text-xs font-medium', i <= step ? 'border-accent text-fg' : 'border-border text-subtle')}>
              {i < step && <Check className="mr-1 inline size-3" />}{s}
            </button>
          </li>
        ))}
      </ol>

      <Card className="flex flex-col gap-4 p-pad">
        {step === 0 && (
          <>
            <Field label="Which day does your salary come?" htmlFor="setup-payday" hint="Budget months start on payday, so a month's money lasts until the next one.">
              <Select id="setup-payday" value={payday} onChange={(e) => setPayday(e.target.value)} className="w-40">
                {Array.from({ length: 28 }, (_, i) => <option key={i + 1} value={i + 1}>{i === 0 ? '1st (calendar month)' : `${i + 1}`}</option>)}
              </Select>
            </Field>
            <Field label="Monthly take-home pay (optional)" htmlFor="setup-income" hint="Only used to suggest first budgets. It isn't shared with anyone.">
              <Input id="setup-income" inputMode="decimal" placeholder="80,000" className="w-48 tabular" value={income} onChange={(e) => setIncome(e.target.value)} />
            </Field>
          </>
        )}

        {step === 1 && (
          <>
            <p className="text-sm text-muted">Where your money is. The balance is what's there today (on a card: what you owe).</p>
            {accounts.map((a, i) => (
              <div key={i} className="grid grid-cols-[8rem_1fr_7rem_auto] items-end gap-2">
                <Field label={i === 0 ? 'Kind' : ''} htmlFor={`acc-kind-${i}`}>
                  <Select id={`acc-kind-${i}`} aria-label={`Account ${i + 1} kind`} value={a.kind}
                    onChange={(e) => setAccounts(accounts.map((x, j) => (j === i ? { ...x, kind: e.target.value as AccountKind } : x)))}>
                    {(Object.keys(ACCOUNT_KINDS) as AccountKind[]).map((k) => <option key={k} value={k}>{ACCOUNT_KINDS[k].label}</option>)}
                  </Select>
                </Field>
                <Field label={i === 0 ? 'Name' : ''} htmlFor={`acc-name-${i}`}>
                  <Input id={`acc-name-${i}`} aria-label={`Account ${i + 1} name`} maxLength={60} placeholder="HDFC savings" value={a.name}
                    onChange={(e) => setAccounts(accounts.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))} />
                </Field>
                <Field label={i === 0 ? 'Balance' : ''} htmlFor={`acc-bal-${i}`}>
                  <Input id={`acc-bal-${i}`} aria-label={`Account ${i + 1} balance`} inputMode="decimal" placeholder="0" className="tabular" value={a.balance}
                    onChange={(e) => setAccounts(accounts.map((x, j) => (j === i ? { ...x, balance: e.target.value } : x)))} />
                </Field>
                <Button variant="ghost" size="icon" aria-label={`Remove account ${i + 1}`} disabled={accounts.length === 1}
                  onClick={() => setAccounts(accounts.filter((_, j) => j !== i))}><Trash2 /></Button>
              </div>
            ))}
            <Button variant="outline" className="self-start" onClick={() => setAccounts([...accounts, { kind: 'BANK', name: '', balance: '' }])}><Plus />Add an account</Button>
          </>
        )}

        {step === 2 && (
          <>
            <p className="text-sm text-muted">Tick what goes out regularly. These become your plan: reminders, and money kept aside in safe to spend.</p>
            <ul className="flex flex-col divide-y divide-border">
              {payments.map((p, i) => (
                <li key={p.key} className="flex flex-wrap items-center gap-2 py-2">
                  <label className="flex min-w-44 flex-1 items-center gap-2 text-sm">
                    <input type="checkbox" checked={p.on} onChange={(e) => setPayment(i, { on: e.target.checked })} className="size-4 accent-[var(--accent)]" />
                    {p.name}{p.everyMonths === 12 && <span className="text-xs text-subtle">(yearly)</span>}
                  </label>
                  {p.on && (
                    <>
                      <Input aria-label={`${p.name} amount`} inputMode="decimal" placeholder="Amount" className="h-8 w-28 tabular" value={p.amount}
                        onChange={(e) => setPayment(i, { amount: e.target.value })} />
                      <Select aria-label={`${p.name} day`} value={p.day} className="h-8 w-24" onChange={(e) => setPayment(i, { day: e.target.value })}>
                        {Array.from({ length: 31 }, (_, d) => <option key={d + 1} value={d + 1}>day {d + 1}</option>)}
                      </Select>
                      {accounts.length > 1 && (
                        <Select aria-label={`${p.name} paid from`} value={p.account} className="h-8 w-36" onChange={(e) => setPayment(i, { account: Number(e.target.value) })}>
                          {accounts.map((a, j) => <option key={j} value={j}>{a.name || `Account ${j + 1}`}</option>)}
                        </Select>
                      )}
                    </>
                  )}
                </li>
              ))}
            </ul>
          </>
        )}

        {step === 3 && (
          <>
            <p className="text-sm text-muted">
              Monthly limits for everyday spending{takeHome ? `, suggested from ${money(takeHome)} take-home` : ''}. Adjust them; you'll see how you're doing all month.
            </p>
            <ul className="flex flex-col gap-2">
              {shownBudgets.map((b, i) => (
                <li key={b.category} className="flex items-center gap-3">
                  <label className="flex flex-1 items-center gap-2 text-sm">
                    <input type="checkbox" checked={b.on} className="size-4 accent-[var(--accent)]"
                      onChange={(e) => setBudgets(budgets.map((x, j) => (j === i ? { ...x, on: e.target.checked } : x)))} />
                    {b.label}
                  </label>
                  <Input aria-label={`${b.label} budget`} inputMode="decimal" placeholder="Amount" className="h-8 w-32 tabular" value={b.amount} disabled={!b.on}
                    onChange={(e) => setBudgets(budgets.map((x, j) => (j === i ? { ...x, amount: e.target.value } : x)))} />
                </li>
              ))}
            </ul>
          </>
        )}

        {over.length > 0 && (
          <div className="flex flex-wrap items-center gap-3 rounded-control bg-warning/10 px-3 py-2 text-sm" role="status">
            <span className="flex-1">The Free plan allows {over.map((k) => `${limits![k]} ${k === 'big-costs' ? 'yearly cost' : k}`).join(', ')}. Untick a few, or try Premium free.</span>
            {plan?.trialAvailable && (
              <Button size="sm" variant="outline" disabled={trial.isPending} onClick={() => trial.mutate()}><Sparkles />Try Premium free</Button>
            )}
          </div>
        )}
        {error && <p className="rounded-control bg-critical/10 px-3 py-2 text-sm text-critical" role="alert">{error}</p>}

        <div className="flex items-center gap-2 pt-2">
          {step > 0 && <Button variant="ghost" onClick={() => setStep(step - 1)}><ChevronLeft />Back</Button>}
          <span className="flex-1" />
          {step < STEPS.length - 1
            ? <Button variant="primary" onClick={() => setStep(step + 1)}>Next<ChevronRight /></Button>
            : <Button variant="primary" onClick={finish} disabled={submit.isPending || over.length > 0}><Check />Finish setup</Button>}
        </div>
      </Card>
      <button type="button" className="self-center text-xs text-muted underline underline-offset-4 hover:text-fg" onClick={() => void navigate({ to: '/accounts' })}>
        Skip: I'll add things myself
      </button>
    </div>
  )
}
