import { useEffect, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { ArrowRight, Check, RefreshCcw, Tag } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/controls'
import { Dialog } from '@/components/ui/overlay'
import { ApiError, api } from '@/lib/api'
import { cn } from '@/lib/cn'
import { allCurrencies, currencySymbol, formatMoney, moneyDefaults, setMoneyDefaults } from '@/lib/money'

type Mode = 'CONVERT' | 'RELABEL'
interface Plan {
  from: string; to: string; mode: Mode; rate: number; rateSource: string; rateDate: string | null
  accounts: number; entries: number; budgets: number; bills: number; goals: number; services: number
  sampleBefore: number; sampleAfter: number
}

/**
 * Moving your money to another currency, with the choice that matters: convert every amount at an exchange rate
 * (moving country), or keep the numbers and change the currency (it was set up wrong). Both previewed before anything
 * changes; the conversion can use your own rate.
 */
export function CurrencyChangeDialog({ spaceId, to, open, onOpenChange, onDone }: {
  spaceId: string; to: string; open: boolean; onOpenChange: (o: boolean) => void; onDone?: () => void
}) {
  const client = useQueryClient()
  const [mode, setMode] = useState<Mode>('CONVERT')
  const [ownRate, setOwnRate] = useState('')
  const [convert, setConvert] = useState<Plan | null>(null)
  const [relabel, setRelabel] = useState<Plan | null>(null)
  const [rateError, setRateError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const name = allCurrencies().find((c) => c.code === to)?.name ?? to

  const rate = ownRate.trim() ? Number(ownRate.replace(',', '.')) : undefined
  useEffect(() => {
    if (!open) return
    setRateError(undefined)
    const q = (m: Mode, r?: number) => api<Plan>(`/api/core/spaces/${spaceId}/currency/preview?to=${to}&mode=${m}${r ? `&rate=${r}` : ''}`)
    void q('RELABEL').then(setRelabel).catch(() => setRelabel(null))
    const t = setTimeout(() => {
      if (rate !== undefined && !(rate > 0)) return setRateError('The rate must be a number above zero.')
      void q('CONVERT', rate).then(setConvert).catch((e) => {
        setConvert(null)
        setRateError(e instanceof ApiError ? `${e.message}.` : 'Today’s rate isn’t available; enter one.')
      })
    }, 300)
    return () => clearTimeout(t)
  }, [open, spaceId, to, rate])

  const plan = mode === 'CONVERT' ? convert : relabel
  const fmt = (minor: number, c: string) => { try { return formatMoney(minor, c) } catch { return `${minor} ${c}` } }

  async function apply() {
    if (!plan) return
    setBusy(true)
    try {
      await api(`/api/core/spaces/${spaceId}/currency`, { method: 'POST', json: { to, mode, rate: mode === 'CONVERT' ? rate : undefined } })
      setMoneyDefaults(to, moneyDefaults().locale)
      await client.invalidateQueries()
      toast.success(mode === 'CONVERT' ? `Converted to ${to}` : `Now in ${to}`, {
        description: mode === 'CONVERT' ? `Every amount moved at ${plan.rate.toPrecision(5)} ${to} per ${plan.from}.` : 'The same numbers, in the new currency.',
      })
      onOpenChange(false)
      onDone?.()
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'Couldn’t change it. Nothing was changed.')
    } finally {
      setBusy(false)
    }
  }

  const moving = plan && [
    plan.accounts && `${plan.accounts} ${plan.accounts === 1 ? 'account' : 'accounts'}`,
    plan.entries && `${plan.entries} ${plan.entries === 1 ? 'entry' : 'entries'}`,
    plan.budgets && `${plan.budgets} ${plan.budgets === 1 ? 'budget' : 'budgets'}`,
    plan.bills && `${plan.bills} ${plan.bills === 1 ? 'bill or saving' : 'bills and savings'}`,
    plan.goals && `${plan.goals} ${plan.goals === 1 ? 'goal' : 'goals'}`,
    plan.services && `${plan.services} daily ${plan.services === 1 ? 'service' : 'services'}`,
  ].filter(Boolean)

  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={`Switch to ${name} (${currencySymbol(to)})?`}
      description="Choose how your money comes across. You'll see the result before anything changes.">
      <div className="flex flex-col gap-3 p-pad" data-testid="currency-change">
        <div role="radiogroup" aria-label="How to change" className="flex flex-col gap-2">
          <Choice on={mode === 'CONVERT'} onSelect={() => setMode('CONVERT')} icon={<RefreshCcw className="size-4" />}
            title="Convert my money" note="Recommended when you move country or want to keep your books in another currency."
            example={convert && <>{fmt(convert.sampleBefore, convert.from)} <ArrowRight className="inline size-3" /> <b>{fmt(convert.sampleAfter, to)}</b></>}>
            {convert && <p className="text-xs text-muted">1 {convert.from} = {convert.rate.toPrecision(5)} {to} · {convert.rateSource}{convert.rateDate && `, ${convert.rateDate}`}</p>}
            {mode === 'CONVERT' && (
              <label className="mt-1 flex items-center gap-2 text-xs text-muted">Use my own rate
                <Input value={ownRate} onChange={(e) => setOwnRate(e.target.value)} inputMode="decimal" placeholder={convert ? String(Number(convert.rate.toPrecision(5))) : '0.012'}
                  aria-label="My own rate" className="h-8 w-28 tabular" />
                <span>{to} per {moneyDefaults().currency}</span>
              </label>
            )}
            {rateError && mode === 'CONVERT' && <p className="text-xs text-critical" role="alert">{rateError}</p>}
          </Choice>
          <Choice on={mode === 'RELABEL'} onSelect={() => setMode('RELABEL')} icon={<Tag className="size-4" />}
            title="Keep the numbers, just change the currency" note="For when the currency was set up wrong: nothing is converted."
            example={relabel && <>{fmt(relabel.sampleBefore, relabel.from)} <ArrowRight className="inline size-3" /> <b>{fmt(relabel.sampleAfter, to)}</b></>} />
        </div>
        {plan && (
          <p className="rounded-control bg-surface-2 p-3 text-xs text-muted">
            {moving && moving.length > 0 ? <>Moves {moving.join(', ')}. </> : <>Nothing is recorded yet, so it simply switches. </>}
            Accounts you keep in another currency, and Friends &amp; groups (each group keeps its own currency), stay as they are.
          </p>
        )}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button variant="primary" disabled={!plan || busy || (mode === 'CONVERT' && !!rateError)} onClick={() => void apply()}>
            {busy ? 'Changing…' : mode === 'CONVERT' ? `Convert to ${to}` : `Switch to ${to}`}</Button>
        </div>
      </div>
    </Dialog>
  )
}

function Choice({ on, onSelect, icon, title, note, example, children }: {
  on: boolean; onSelect: () => void; icon: React.ReactNode; title: string; note: string; example?: React.ReactNode; children?: React.ReactNode
}) {
  return (
    <div role="radio" aria-checked={on} tabIndex={0} onClick={onSelect} onKeyDown={(e) => { if (e.key === ' ' || e.key === 'Enter') onSelect() }}
      className={cn('flex cursor-pointer flex-col gap-1 rounded-card border p-3 text-left transition-colors', on ? 'border-accent bg-accent-soft' : 'border-border hover:border-border-strong')}>
      <span className="flex items-center gap-2 font-medium">
        <span className={cn('flex size-7 items-center justify-center rounded-full', on ? 'bg-accent text-accent-fg' : 'bg-surface-2 text-muted')}>{on ? <Check className="size-4" /> : icon}</span>
        {title}
      </span>
      <span className="pl-9 text-xs text-muted">{note}</span>
      {example && <span className="pl-9 text-sm tabular">{example}</span>}
      {children && <div className="pl-9" onClick={(e) => e.stopPropagation()}>{children}</div>}
    </div>
  )
}
