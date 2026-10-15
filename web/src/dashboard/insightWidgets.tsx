import { useState, type FormEvent } from 'react'
import { MoneyInput } from '@/components/ui/money-input'
import { AlertTriangle, CheckCircle2, ChevronDown, CircleSlash, HeartPulse, MessageCircle, Share2 } from 'lucide-react'
import { Link } from '@tanstack/react-router'
import { toast } from 'sonner'
import { whatsAppShare } from '@/lib/whatsapp'
import { motion } from 'motion/react'
import { Button } from '@/components/ui/button'
import { Segmented } from '@/components/ui/controls'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/cn'
import { formatMoneyWhole, parseMoney } from '@/lib/money'
import { affordCheck, useHealth, useMonthReview, type Afford } from '@/state/insights'
import { useSpaceId } from '@/state/money'

const STATUS = {
  good: { icon: CheckCircle2, label: 'Good', className: 'text-good-text' },
  fair: { icon: AlertTriangle, label: 'Fair', className: 'text-fg' },
  weak: { icon: CircleSlash, label: 'Needs care', className: 'text-critical' },
} as const

/** A 0–100 score from a few plain checks; each opens to what it means and what to do. */
export function MoneyHealth() {
  const { data, isPending, isError } = useHealth()
  const [open, setOpen] = useState<string | null>(null)
  if (isPending) return <div className="h-40 animate-pulse rounded-control bg-surface-2" />
  if (isError || !data) return <p className="text-sm text-muted">Couldn’t work out your score just now.</p>
  if (data.score === null) {
    return (
      <div className="flex min-h-24 items-center gap-3 text-sm text-muted">
        <HeartPulse className="size-8 shrink-0 text-accent" />
        Record a month of income and spending, and your money health score appears here.
      </div>
    )
  }
  const r = 34
  const c = 2 * Math.PI * r
  return (
    <div className="flex flex-col gap-3 sm:flex-row sm:items-start" data-testid="money-health">
      <div className="relative flex size-24 shrink-0 items-center justify-center self-center">
        <svg viewBox="0 0 80 80" className="absolute inset-0 -rotate-90" aria-hidden>
          <circle cx="40" cy="40" r={r} fill="none" stroke="var(--surface-2)" strokeWidth="8" />
          <motion.circle cx="40" cy="40" r={r} fill="none" strokeWidth="8" strokeLinecap="round"
            stroke={data.score >= 60 ? 'var(--good)' : data.score >= 40 ? 'var(--warning)' : 'var(--critical)'}
            strokeDasharray={c} initial={{ strokeDashoffset: c }} animate={{ strokeDashoffset: c * (1 - data.score / 100) }}
            transition={{ duration: 1, ease: [0.2, 0.8, 0.2, 1] }} />
        </svg>
        <span className="text-center leading-none">
          <span className="block text-2xl font-semibold tabular" data-testid="health-score">{data.score}</span>
          <span className="text-[0.6875rem] text-muted">{data.grade}</span>
        </span>
      </div>
      <ul className="flex min-w-0 flex-1 flex-col gap-1">
        {data.parts.map((p) => {
          const s = STATUS[p.status]
          const shown = open === p.key
          return (
            <li key={p.key}>
              <button type="button" onClick={() => setOpen(shown ? null : p.key)} aria-expanded={shown}
                className="flex w-full items-center gap-2 rounded-control px-1.5 py-1 text-left text-sm hover:bg-surface-2">
                <s.icon className={cn('size-4 shrink-0', s.className)} aria-label={s.label} />
                <span className="min-w-0 flex-1 truncate">{p.title}</span>
                <span className="text-xs text-muted tabular">{p.points}/{p.max}</span>
                <ChevronDown className={cn('size-3.5 text-subtle transition-transform', shown && 'rotate-180')} />
              </button>
              {shown && <p className="px-7 pb-1.5 text-xs text-muted">{p.detail} <span className="text-fg">{p.tip}</span></p>}
            </li>
          )
        })}
      </ul>
    </div>
  )
}

const VERDICT = {
  YES: { icon: CheckCircle2, className: 'border-good/40 bg-good/10', iconClass: 'text-good-text' },
  MAYBE: { icon: AlertTriangle, className: 'border-warning/50 bg-warning/10', iconClass: 'text-fg' },
  NO: { icon: CircleSlash, className: 'border-critical/40 bg-critical/10', iconClass: 'text-critical' },
} as const

/** "Can I afford it?" for a one-off or a monthly cost, answered from this month's plan and the last three months. */
export function CanIAfford() {
  const spaceId = useSpaceId()
  const [amount, setAmount] = useState('')
  const [often, setOften] = useState<'once' | 'monthly'>('once')
  const [answer, setAnswer] = useState<Afford | null>(null)
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  async function check(e: FormEvent) {
    e.preventDefault()
    const paise = parseMoney(amount)
    if (!paise || paise <= 0 || !spaceId) return setError('Enter what it costs.')
    setError(undefined)
    setBusy(true)
    try {
      setAnswer(await affordCheck(spaceId, paise, often === 'monthly'))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Couldn’t check just now.')
    } finally {
      setBusy(false)
    }
  }
  const v = answer ? VERDICT[answer.verdict] : null
  return (
    <form onSubmit={check} className="flex flex-col gap-3" data-testid="can-i-afford">
      <div className="flex flex-wrap items-end gap-2">
        <label className="flex min-w-32 flex-1 flex-col gap-1 text-xs font-medium text-muted">It costs (₹)
          <MoneyInput value={amount} onChange={(e) => { setAmount(e.target.value); setAnswer(null) }} placeholder="45,000" aria-label="It costs" />
        </label>
        <Segmented label="How often" value={often} onChange={(m) => { setOften(m); setAnswer(null) }}
          options={[{ value: 'once', label: 'Once' }, { value: 'monthly', label: 'Every month' }]} />
        <Button variant="primary" type="submit" disabled={busy}>{busy ? 'Checking…' : 'Check'}</Button>
      </div>
      {error && <p className="text-sm text-critical" role="alert">{error}</p>}
      {answer && v ? (
        <motion.div initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} className={cn('flex gap-3 rounded-control border p-3', v.className)} role="status">
          <v.icon className={cn('mt-0.5 size-5 shrink-0', v.iconClass)} />
          <div className="min-w-0">
            <p className="font-medium">{answer.headline}</p>
            <ul className="mt-1 flex flex-col gap-0.5 text-sm text-muted">{answer.reasons.map((x) => <li key={x}>{x}</li>)}</ul>
          </div>
        </motion.div>
      ) : !error && (
        <p className="text-xs text-muted">A phone, a trip, a new EMI: we check it against this month’s plan, what you usually have left, and your savings cushion.</p>
      )}
    </form>
  )
}

/** Last month in a glance: kept or overspent, spending against the month before, the first highlight. */
export function LastMonth() {
  const { data, isPending } = useMonthReview()
  if (isPending) return <div className="h-24 animate-pulse rounded-control bg-surface-2" />
  if (!data || data.entries === 0) return <p className="text-sm text-muted">Record a month of money, and its review shows up here on the 1st.</p>
  const change = data.spentBefore > 0 ? Math.round(((data.spent - data.spentBefore) / data.spentBefore) * 100) : null
  return (
    <Link to="/review" search={{ month: data.month }} className="-m-1 flex flex-col gap-2 rounded-control p-1 hover:bg-surface-2/50" data-testid="last-month">
      <span className="text-xs text-muted">{new Date(`${data.month}-01T00:00`).toLocaleDateString(undefined, { month: 'long' })}</span>
      <span className={cn('text-2xl font-semibold tabular', data.saved >= 0 ? 'text-good-text' : 'text-critical')}>
        {data.saved >= 0 ? 'Kept ' : 'Over by '}{formatMoneyWhole(Math.abs(data.saved), data.currency)}</span>
      {change !== null && <span className="text-xs text-muted">Spending {change >= 0 ? 'up' : 'down'} {Math.abs(change)}% on the month before</span>}
      {data.highlights[1] && <span className="text-sm">{data.highlights[1]}</span>}
      <span className="text-xs font-medium text-fg underline underline-offset-4">Read the review</span>
    </Link>
  )
}

/** Growth that helps people: share PayLedger with whoever you split money with (WhatsApp, or the phone's share sheet). */
export function BringAFriend() {
  const url = `${location.origin}/`
  const text = 'I keep my budget and split expenses with friends on PayLedger. Free to start:'
  async function share() {
    if (navigator.share) {
      try { await navigator.share({ title: 'PayLedger', text, url }); return } catch { /* closed: fall through to copy */ }
    }
    await navigator.clipboard?.writeText(`${text} ${url}`)
    toast.success('Link copied', { description: 'Paste it to a friend.' })
  }
  return (
    <div className="flex flex-col gap-3" data-testid="bring-a-friend">
      <p className="text-sm">Splitting is easier when everyone's here. Invite the people you share costs with: they join in a minute, and groups settle themselves.</p>
      <div className="flex flex-wrap gap-2">
        <Button variant="primary" size="sm" onClick={() => void share()}><Share2 />Share PayLedger</Button>
        <Button variant="outline" size="sm" asChild><a href={whatsAppShare(`${text} ${url}`)} target="_blank" rel="noreferrer"><MessageCircle />WhatsApp</a></Button>
      </div>
    </div>
  )
}
