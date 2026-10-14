import { useState, type ReactNode } from 'react'
import { Link, useNavigate } from '@tanstack/react-router'
import { Lock } from 'lucide-react'
import { Card, Skeleton } from '@/components/ui/card'
import { Segmented } from '@/components/ui/controls'
import { BarList, Progress } from '@/dashboard/charts'
import { cn } from '@/lib/cn'
import { useCan, useOverview, type Overview } from '@/state/admin'
import { useProfile } from '@/state/profile'
import { DailyChart } from './DailyChart'

const RANGES = [{ value: '7', label: '7 days' }, { value: '30', label: '30 days' }, { value: '90', label: '90 days' }] as const

/** The business at a glance: people, activity, plans, what they use, and whether they come back. */
export function AdminOverview() {
  const [range, setRange] = useState<'7' | '30' | '90'>('30')
  const { data, isPending, isError } = useOverview(Number(range))
  const navigate = useNavigate()
  const canSeeUsers = useCan('USERS_VIEW')
  const locale = useProfile().data?.settings.locale ?? 'en-IN'
  const n = (v: number) => v.toLocaleString(locale)
  const pct = (v: number, of: number) => (of > 0 ? `${Math.round((v / of) * 100)}%` : '—')

  if (isError) return <Card className="p-pad text-sm text-critical">Couldn't load the numbers. Refresh to try again.</Card>
  if (isPending || !data) return <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">{Array.from({ length: 8 }, (_, i) => <Skeleton key={i} className="h-28" />)}</div>

  const premium = data.plans.trial + data.plans.paid + data.plans.granted + data.plans.complimentary
  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="flex items-center gap-1.5 text-xs text-subtle">
          <Lock className="size-3.5" />Counts only: staff never see anyone's amounts, descriptions or notes. Days in {data.zone}.
        </p>
        <Segmented label="Period" value={range} onChange={setRange} options={[...RANGES]} />
      </div>

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Kpi label="Users" value={n(data.users.total)} note={`+${n(data.users.new7Days)} this week · +${n(data.users.newToday)} today`} testId="kpi-users" show={canSeeUsers ? 'all' : undefined} />
        <Kpi label="Active today" value={n(data.active.daily)} note={`${pct(data.active.daily, data.active.monthly)} of monthly active (stickiness)`} testId="kpi-daily" />
        <Kpi label="Active in 7 days" value={n(data.active.weekly)} note={`${pct(data.active.weekly, data.users.total)} of all users`} />
        <Kpi label="Active in 30 days" value={n(data.active.monthly)} note={`${pct(data.active.monthly, data.users.total)} of all users`} />
        <Kpi label="Premium" show={canSeeUsers ? 'PREMIUM' : undefined} value={n(premium)} note={`${n(data.plans.paid)} paying · ${n(data.plans.trial)} on trial · ${n(data.plans.granted + data.plans.complimentary)} given`} testId="kpi-premium" />
        <Kpi label="Trials ending in 7 days" show={canSeeUsers ? 'TRIAL' : undefined} value={n(data.plans.trialsEndingSoon)} note={`${n(data.plans.trialsStarted30Days)} trials started in 30 days`} />
        <Kpi label="Trial to paid" value={n(data.plans.convertedFromTrial)} note="paying users who tried Premium first" />
        <Kpi label="Sign-ups in 30 days" value={n(data.users.new30Days)} note={`${n(data.usage.transactions)} transactions added in 30 days`} />
      </div>

      <section aria-label="Platform" className="flex flex-col gap-2">
        <h3 className="text-sm font-medium text-muted">The last 24 hours</h3>
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          <Health label="Requests" value={n(data.platform.requests24h)} note={`95% answered within ${n(data.platform.p95Ms)} ms`} tab="requests" />
          <Health label="Errors" value={n(data.platform.serverErrors24h + data.platform.clientErrors24h)} tab="requests" errors
            note={`${n(data.platform.serverErrors24h)} on our side \u00b7 ${n(data.platform.clientErrors24h)} refused or invalid`} bad={data.platform.serverErrors24h > 0} />
          <Health label="Sign-ins" value={n(data.platform.signIns24h)} note={`${n(data.platform.signUps24h)} new sign-ups`} tab="activity" />
          <Health label="Emails" value={n(data.platform.emailsPending)} note={`waiting \u00b7 ${n(data.platform.emailsFailed)} gave up`} tab="emails" bad={data.platform.emailsFailed > 0} />
        </div>
      </section>

      <Card className="flex flex-col gap-3 p-pad">
        <h3 className="font-medium">Active users and sign-ups per day</h3>
        <DailyChart days={data.days} locale={locale} />
      </Card>

      <div className="grid gap-4 lg:grid-cols-2">
        <Section title="Plans" note={`${n(data.users.total)} users`}>
          <BarList format={n} onSelect={canSeeUsers ? (key) => void navigate({ to: '/admin', search: { tab: 'users', show: key === 'free' ? 'FREE' : key === 'trial' ? 'TRIAL' : key === 'granted' ? 'GRANTED' : 'PREMIUM' } }) : undefined} items={[
            { key: 'free', label: 'Free', value: data.plans.free, slot: 0 },
            { key: 'trial', label: 'Premium trial', value: data.plans.trial, slot: 3 },
            { key: 'paid', label: 'Premium, paying', value: data.plans.paid, slot: 1 },
            { key: 'granted', label: 'Premium, given by staff', value: data.plans.granted, slot: 5 },
            { key: 'comp', label: 'Premium, complimentary', value: data.plans.complimentary, slot: 4 },
          ].filter((i) => i.value > 0)} />
        </Section>
        <Section title="Feature adoption" note="share of users who have ever…">
          <ul className="flex flex-col gap-3">
            {([
              ['added an account', data.adoption.accounts],
              ['added a transaction', data.adoption.transactions],
              ['set a budget', data.adoption.budgets],
              ['planned a bill or saving', data.adoption.bills],
              ['imported a file', data.adoption.imports],
              ['joined a group', data.adoption.groups],
            ] as const).map(([label, value]) => (
              <li key={label}>
                <div className="mb-1 flex justify-between text-sm"><span>{label}</span>
                  <span className="tabular text-muted">{pct(value, data.adoption.users)} · {n(value)}</span></div>
                <Progress value={data.adoption.users ? value / data.adoption.users : 0} label={label} />
              </li>
            ))}
          </ul>
        </Section>
      </div>

      <Section title="Do people come back?" note="share of each week's sign-ups active in the weeks after">
        <Retention cohorts={data.cohorts} locale={locale} />
      </Section>
    </div>
  )
}

/** Health over the last day; opens the log behind it. */
function Health({ label, value, note, tab, errors, bad }: { label: string; value: string; note: string; tab: 'requests' | 'activity' | 'emails'; errors?: boolean; bad?: boolean }) {
  return (
    <Link to="/admin" search={{ tab, show: errors ? 'errors' : undefined }} data-testid={`health-${label.toLowerCase()}`}
      className={cn('flex flex-col gap-1 rounded-card border bg-surface p-pad shadow-card transition-colors hover:bg-surface-2', bad ? 'border-critical/50' : 'border-border hover:border-border-strong')}>
      <span className="text-sm text-muted">{label}</span>
      <span className={cn('text-2xl font-semibold tracking-tight tabular', bad && 'text-critical')}>{value}</span>
      <span className="text-xs text-subtle">{note}</span>
    </Link>
  )
}

/** A number; with {@code show}, it opens the users behind it. */
function Kpi({ label, value, note, testId, show }: { label: string; value: string; note: string; testId?: string; show?: 'all' | 'PREMIUM' | 'TRIAL' }) {
  const body = (
    <>
      <span className="text-sm text-muted">{label}</span>
      <span className="text-2xl font-semibold tracking-tight tabular">{value}</span>
      <span className="text-xs text-subtle">{note}</span>
    </>
  )
  if (!show) return <Card className="flex flex-col gap-1 p-pad" data-testid={testId}>{body}</Card>
  return (
    <Link to="/admin" search={{ tab: 'users', show: show === 'all' ? undefined : show }} data-testid={testId} aria-label={`${label}: ${value}. Open the list`}
      className="flex flex-col gap-1 rounded-card border border-border bg-surface p-pad shadow-card transition-colors hover:border-border-strong hover:bg-surface-2">
      {body}
    </Link>
  )
}

function Section({ title, note, children }: { title: string; note?: string; children: ReactNode }) {
  return (
    <Card className="flex flex-col gap-4 p-pad">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="font-medium">{title}</h3>
        {note && <span className="text-xs text-subtle">{note}</span>}
      </div>
      {children}
    </Card>
  )
}

/** Weekly cohorts as a table: the darker the cell, the more of that week's sign-ups came back. */
function Retention({ cohorts, locale }: { cohorts: Overview['cohorts']; locale: string }) {
  const weeks = Math.max(...cohorts.map((c) => c.retained.length), 1)
  return (
    <div className="-mx-pad overflow-x-auto px-pad">
      <table className="w-full min-w-[560px] border-separate border-spacing-1 text-xs">
        <thead>
          <tr className="text-subtle">
            <th className="text-left font-medium">Signed up</th>
            <th className="text-right font-medium">People</th>
            {Array.from({ length: weeks }, (_, k) => <th key={k} className="font-medium">{k === 0 ? 'Week 0' : `+${k}`}</th>)}
          </tr>
        </thead>
        <tbody>
          {cohorts.map((c) => (
            <tr key={c.week}>
              <td className="whitespace-nowrap text-muted">{new Date(`${c.week}T00:00:00`).toLocaleDateString(locale, { day: 'numeric', month: 'short' })}</td>
              <td className="text-right tabular">{c.size}</td>
              {Array.from({ length: weeks }, (_, k) => {
                const v = c.retained[k]
                return (
                  <td key={k} className={cn('h-8 min-w-11 rounded-[4px] text-center tabular', v === undefined || c.size === 0 ? 'text-subtle' : v >= 0.5 ? 'text-accent-fg' : 'text-fg')}
                    style={v !== undefined && c.size > 0 ? { background: `color-mix(in oklab, var(--accent) ${Math.round(8 + v * 92)}%, transparent)` } : undefined}>
                    {v === undefined || c.size === 0 ? '' : `${Math.round(v * 100)}%`}
                  </td>
                )
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
