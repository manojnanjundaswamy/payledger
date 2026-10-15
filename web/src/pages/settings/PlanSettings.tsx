import { Link } from '@tanstack/react-router'
import { Check, Minus, Sparkles } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Badge, Card, Skeleton } from '@/components/ui/card'
import { Progress } from '@/dashboard/charts'
import { ApiError } from '@/lib/api'
import { LIMIT_LABELS, PLAN_LINES, PRICE } from '@/lib/plans'
import { usePlan, useStartTrial } from '@/state/plan'
import { useProfile } from '@/state/profile'

/** Where each limited thing lives, so a limit opens its page. */
const LIMIT_PAGES: Record<string, string> = { accounts: '/accounts', budgets: '/budgets', bills: '/plan', 'big-costs': '/plan', imports: '/import', goals: '/goals', ai: '/dashboard' }

/** Your plan, what Free's limits leave, the trial, and what Premium adds. */
export function PlanSettings() {
  const { data: plan, isPending, isError } = usePlan()
  const locale = useProfile().data?.settings.locale ?? 'en-IN'
  const trial = useStartTrial()

  if (isError) return <Card className="p-pad text-sm text-critical">Couldn't load your plan. Refresh to try again.</Card>
  if (isPending || !plan) return <Skeleton className="h-64" />

  const premium = plan.plan === 'PREMIUM'
  const until = plan.premiumUntil && new Date(plan.premiumUntil).toLocaleDateString(locale, { day: 'numeric', month: 'long', year: 'numeric' })

  async function startTrial() {
    try {
      await trial.mutateAsync()
      toast.success('Premium is on for 30 days', { description: 'No card needed. Nothing is lost when it ends.' })
    } catch (err) {
      toast.error(err instanceof ApiError && err.code === 'trial-used' ? 'The trial was already used' : 'Could not start the trial. Try again.')
    }
  }

  return (
    <div className="flex max-w-2xl flex-col gap-6">
      <Card className="flex flex-col gap-4 p-pad">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <p className="text-sm text-muted">Your plan</p>
            <p className="flex items-center gap-2 text-2xl font-semibold tracking-tight" data-testid="plan-name">
              {premium ? <><Sparkles className="size-5" />Premium</> : 'Free'}
            </p>
            <p className="text-sm text-muted">
              {plan.source === 'COMPLIMENTARY' && 'Complimentary: on us.'}
              {plan.source === 'GRANTED' && until && `Given by PayLedger until ${until}.`}
              {plan.source === 'TRIAL' && until && `Trial until ${until}.`}
              {plan.source === 'PAID' && until && `Renews on ${until}.`}
              {!premium && 'A complete budget, free for good. Limits only stop adding, never viewing or exporting.'}
            </p>
          </div>
          {!premium && plan.trialAvailable && (
            <Button variant="primary" onClick={startTrial} disabled={trial.isPending}><Sparkles />Try Premium free for 30 days</Button>
          )}
        </div>

        {plan.limits && (
          <ul className="flex flex-col gap-3" aria-label="Free plan limits">
            {Object.entries(plan.limits).map(([key, max]) => {
              const used = plan.usage[key] ?? 0
              return (
                <li key={key} data-testid={`limit-${key}`}>
                  <div className="mb-1 flex justify-between text-sm">
                    <Link to={LIMIT_PAGES[key] ?? '/dashboard'} className="hover:underline hover:underline-offset-4">{LIMIT_LABELS[key] ?? key}</Link>
                    <span className="tabular text-muted">{used} of {max}</span>
                  </div>
                  <Progress value={used / max} color={used >= max ? 'var(--warning)' : 'var(--accent)'} label={`${LIMIT_LABELS[key] ?? key} used`} />
                </li>
              )
            })}
          </ul>
        )}
      </Card>

      <section aria-label="Compare plans" className="flex flex-col gap-2">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h3 className="text-base font-semibold">Free and Premium</h3>
          <p className="text-sm text-muted">Premium: {PRICE.monthly} a month, or {PRICE.yearly} a year ({PRICE.yearlySaving})</p>
        </div>
        <PlanTable />
        {!premium && (
          <p className="text-xs text-subtle">Paying for Premium is coming soon (UPI AutoPay and cards). Until then, the 30-day trial has everything.</p>
        )}
      </section>
    </div>
  )
}

/** Free against Premium, shared with the landing page. */
export function PlanTable() {
  return (
    <Card className="divide-y divide-border p-0 text-sm">
      <div className="grid grid-cols-[1fr_7rem_7rem] gap-2 px-pad py-2.5 text-xs font-medium text-muted sm:grid-cols-[1fr_9rem_9rem]">
        <span>Feature</span><span>Free</span><span className="flex items-center gap-1">Premium <Badge tone="accent">{PRICE.monthly}/mo</Badge></span>
      </div>
      {PLAN_LINES.map((line) => (
        <div key={line.feature} className="grid grid-cols-[1fr_7rem_7rem] items-center gap-2 px-pad py-2.5 sm:grid-cols-[1fr_9rem_9rem]">
          <span>{line.feature}</span>
          <Cell value={line.free} />
          <Cell value={line.premium} />
        </div>
      ))}
    </Card>
  )
}

function Cell({ value }: { value: string | boolean }) {
  if (value === true) return <span aria-label="Included"><Check className="size-4 text-good-text" /></span>
  if (value === false) return <span aria-label="Not included"><Minus className="size-4 text-subtle" /></span>
  return <span className="text-muted">{value}</span>
}
