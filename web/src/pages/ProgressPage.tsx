import { useMemo } from 'react'
import { Link } from '@tanstack/react-router'
import { motion } from 'motion/react'
import {
  CalendarClock, CheckCircle2, Flame, Goal, HandCoins, Home, Inbox, Leaf, Lock, Pencil, PieChart, PiggyBank, Shield, Split, Target, Trophy,
  type LucideIcon,
} from 'lucide-react'
import { Card, Skeleton } from '@/components/ui/card'
import { cn } from '@/lib/cn'
import { usePreferences } from '@/state/PreferencesProvider'
import { useProgress, type Badge, type Progress } from '@/state/progress'

export const BADGE_ICONS: Record<string, LucideIcon> = {
  pencil: Pencil, 'pie-chart': PieChart, 'calendar-clock': CalendarClock, goal: Goal, flame: Flame, leaf: Leaf, inbox: Inbox,
  'check-circle': CheckCircle2, 'piggy-bank': PiggyBank, shield: Shield, trophy: Trophy, split: Split, 'hand-coins': HandCoins, home: Home,
}

/** Your progress: level, streak, this week's challenge, and every badge (earned, or how to earn it). */
export function ProgressPage() {
  const { prefs, dispatch } = usePreferences()
  const { data, isPending } = useProgress(prefs.gamification)
  if (!prefs.gamification) {
    return (
      <Card className="mx-auto flex max-w-md flex-col items-center gap-3 p-pad text-center">
        <Trophy className="size-8 text-muted" />
        <p className="font-medium">Streaks and badges are off</p>
        <button type="button" className="text-sm underline" onClick={() => dispatch({ type: 'gamification', on: true })}>Turn them on</button>
      </Card>
    )
  }
  if (isPending || !data) return <Skeleton className="h-96" />
  const groups = [...new Set(data.badges.map((b) => b.group))]
  const earned = data.badges.filter((b) => b.earnedAt).length
  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-5">
      <div className="grid gap-4 md:grid-cols-3">
        <LevelCard data={data} />
        <StreakCard data={data} />
        <ChallengeCard data={data} />
      </div>
      <div className="flex items-baseline justify-between">
        <h2 className="text-lg font-semibold">Badges</h2>
        <span className="text-sm text-muted">{earned} of {data.badges.length} earned</span>
      </div>
      {groups.map((g) => (
        <section key={g} className="flex flex-col gap-2">
          <h3 className="text-xs font-medium tracking-wider text-subtle uppercase">{g}</h3>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {data.badges.filter((b) => b.group === g).map((b) => <BadgeCard key={b.key} badge={b} />)}
          </div>
        </section>
      ))}
      <p className="text-center text-xs text-subtle">Badges reward habits that help: recording your money, staying on budget, saving. Never spending.
        {' '}<button type="button" className="underline" onClick={() => dispatch({ type: 'gamification', on: false })}>Turn streaks and badges off</button></p>
    </div>
  )
}

export function LevelRing({ data, size = 88 }: { data: Progress; size?: number }) {
  const span = data.nextAt ? data.nextAt - data.levelFloor : 1
  const share = data.nextAt ? (data.points - data.levelFloor) / span : 1
  const r = 34
  const c = 2 * Math.PI * r
  return (
    <span className="relative flex shrink-0 items-center justify-center" style={{ width: size, height: size }}>
      <svg viewBox="0 0 80 80" className="absolute inset-0 -rotate-90" aria-hidden>
        <circle cx="40" cy="40" r={r} fill="none" stroke="var(--surface-2)" strokeWidth="7" />
        <motion.circle cx="40" cy="40" r={r} fill="none" stroke="var(--accent)" strokeWidth="7" strokeLinecap="round" strokeDasharray={c}
          initial={{ strokeDashoffset: c }} animate={{ strokeDashoffset: c * (1 - share) }} transition={{ duration: 1, ease: [0.2, 0.8, 0.2, 1] }} />
      </svg>
      <Trophy className="size-7 text-accent" />
    </span>
  )
}

function LevelCard({ data }: { data: Progress }) {
  return (
    <Card className="flex items-center gap-4 self-start p-4 md:self-stretch" data-testid="level">
      <LevelRing data={data} />
      <div className="min-w-0">
        <p className="text-xs text-muted">Level</p>
        <p className="text-xl font-semibold">{data.level}</p>
        <p className="text-xs text-muted tabular">{data.points} points{data.nextAt && data.nextLevel && ` · ${data.nextAt - data.points} to ${data.nextLevel}`}</p>
      </div>
    </Card>
  )
}

function StreakCard({ data }: { data: Progress }) {
  const { current, best, today } = data.streak
  // The last 4 weeks, oldest first, Monday-aligned.
  const cells = useMemo(() => {
    const set = new Set(data.streak.days)
    const end = new Date()
    const start = new Date(end)
    start.setDate(end.getDate() - 27 - ((end.getDay() + 6) % 7))
    const out: { key: string; on: boolean; future: boolean }[] = []
    for (let d = new Date(start); out.length < 35; d.setDate(d.getDate() + 1)) {
      const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
      out.push({ key, on: set.has(key), future: d > end })
    }
    return out
  }, [data.streak.days])
  return (
    <Card className="flex flex-col gap-3 p-4" data-testid="streak">
      <div className="flex items-center gap-3">
        <motion.span className={cn('flex size-11 items-center justify-center rounded-full', current > 0 ? 'bg-accent text-accent-fg' : 'bg-surface-2 text-muted')}
          animate={current > 0 ? { scale: [1, 1.08, 1] } : undefined} transition={{ duration: 1.8, repeat: Infinity }}>
          <Flame className="size-6" />
        </motion.span>
        <div>
          <p className="text-xl font-semibold tabular">{current} {current === 1 ? 'day' : 'days'}</p>
          <p className="text-xs text-muted">{current === 0 ? 'Record something today to start a streak.' : today ? 'Kept today. Best: ' + best : 'Record today to keep it going.'}</p>
        </div>
      </div>
      <div className="grid w-fit grid-cols-7 gap-1" aria-label="Days you recorded your money, last weeks">
        {cells.map((c) => <span key={c.key} title={c.key} className={cn('size-4 rounded-[3px]', c.future ? 'bg-transparent' : c.on ? 'bg-accent' : 'bg-surface-2')} />)}
      </div>
    </Card>
  )
}

function ChallengeCard({ data }: { data: Progress }) {
  const c = data.challenge
  return (
    <Card className={cn('flex flex-col gap-2 p-4', c.done && 'border-good/40')} data-testid="challenge">
      <p className="flex items-center gap-1.5 text-xs text-muted"><Target className="size-3.5" />This week's challenge</p>
      <p className="font-semibold">{c.title}</p>
      <p className="text-xs text-muted">{c.how}</p>
      <div className="mt-auto flex items-center gap-2">
        <span className="h-2 flex-1 overflow-hidden rounded-full bg-surface-2">
          <motion.span className={cn('block h-full rounded-full', c.done ? 'bg-good' : 'bg-accent')} initial={{ width: 0 }} animate={{ width: `${(c.progress / c.target) * 100}%` }} />
        </span>
        <span className="text-xs tabular text-muted">{c.done ? 'Done!' : `${c.progress}/${c.target}`}</span>
      </div>
    </Card>
  )
}

function BadgeCard({ badge: b }: { badge: Badge }) {
  const Icon = BADGE_ICONS[b.icon] ?? Trophy
  const on = !!b.earnedAt
  return (
    <div className={cn('flex items-start gap-3 rounded-card border p-3', on ? 'border-accent/40 bg-accent-soft' : 'border-border bg-surface')} data-earned={on}>
      <span className={cn('relative flex size-11 shrink-0 items-center justify-center rounded-full', on ? 'bg-accent text-accent-fg shadow-card' : 'bg-surface-2 text-subtle')}>
        <Icon className="size-5" />
        {!on && <Lock className="absolute -right-0.5 -bottom-0.5 size-3.5 rounded-full bg-surface p-0.5 text-subtle" />}
      </span>
      <div className="min-w-0 flex-1">
        <p className={cn('text-sm font-medium', !on && 'text-muted')}>{b.title}</p>
        <p className="text-xs text-muted">{b.how}</p>
        {on ? (
          <p className="mt-1 text-[0.6875rem] text-subtle">Earned {new Date(b.earnedAt!).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })} · +{b.points}</p>
        ) : b.progress !== null && b.target ? (
          <div className="mt-1.5 flex items-center gap-2">
            <span className="h-1.5 flex-1 overflow-hidden rounded-full bg-surface-2"><span className="block h-full rounded-full bg-accent" style={{ width: `${(b.progress / b.target) * 100}%` }} /></span>
            <span className="text-[0.6875rem] tabular text-subtle">{b.target === 100 ? `${b.progress}%` : `${b.progress}/${b.target}`}</span>
          </div>
        ) : <p className="mt-1 text-[0.6875rem] text-subtle">+{b.points} points</p>}
      </div>
    </div>
  )
}

/** The dashboard card: level, streak and this week's challenge at a glance. */
export function ProgressWidget() {
  const { prefs } = usePreferences()
  const { data } = useProgress(prefs.gamification)
  if (!prefs.gamification) return <p className="text-sm text-muted">Streaks and badges are off. <Link to="/progress" className="underline">Progress</Link></p>
  if (!data) return <div className="h-28 animate-pulse rounded-control bg-surface-2" />
  const latest = data.badges.filter((b) => b.earnedAt).sort((a, b) => b.earnedAt!.localeCompare(a.earnedAt!)).slice(0, 4)
  return (
    <Link to="/progress" className="-m-1 flex flex-col gap-3 rounded-control p-1 hover:bg-surface-2/50" data-testid="progress-widget">
      <div className="flex items-center gap-3">
        <LevelRing data={data} size={56} />
        <div className="min-w-0 flex-1">
          <p className="font-semibold">{data.level} <span className="text-xs font-normal text-muted tabular">· {data.points} pts</span></p>
          <p className="flex items-center gap-1 text-sm text-muted"><Flame className={cn('size-4', data.streak.current ? 'text-accent' : 'text-subtle')} />
            {data.streak.current} day streak{!data.streak.today && data.streak.current > 0 && ' · record today'}</p>
        </div>
      </div>
      <div className="flex items-center gap-2 text-xs">
        <Target className="size-3.5 shrink-0 text-muted" />
        <span className="min-w-0 flex-1 truncate">{data.challenge.title}</span>
        <span className="tabular text-muted">{data.challenge.done ? 'Done' : `${data.challenge.progress}/${data.challenge.target}`}</span>
      </div>
      {latest.length > 0 && (
        <div className="flex gap-1.5" aria-label="Latest badges">
          {latest.map((b) => { const Icon = BADGE_ICONS[b.icon] ?? Trophy; return <span key={b.key} title={b.title} className="flex size-8 items-center justify-center rounded-full bg-accent text-accent-fg"><Icon className="size-4" /></span> })}
        </div>
      )}
    </Link>
  )
}
