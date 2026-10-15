import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { motion } from 'motion/react'
import { ArrowDownRight, ArrowUpRight, CalendarOff, ChevronLeft, ChevronRight, PiggyBank, Sparkles, Target } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, Skeleton } from '@/components/ui/card'
import { cn } from '@/lib/cn'
import { formatMoneyWhole } from '@/lib/money'
import { useMonthReview, type MonthReview } from '@/state/insights'
import { useCategories } from '@/state/money'
import { CategoryIcon } from './parts'

const lastMonth = () => { const d = new Date(); d.setDate(1); d.setMonth(d.getMonth() - 1); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}` }
const shift = (m: string, by: number) => { const [y, mo] = m.split('-').map(Number); const d = new Date(y!, mo! - 1 + by, 1); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}` }
const monthName = (m: string) => { const [y, mo] = m.split('-').map(Number); return new Date(y!, mo! - 1, 1).toLocaleDateString(undefined, { month: 'long', year: 'numeric' }) }

/** A month looked back on: in, out, kept, what moved, the biggest spends, and a few things worth knowing. */
export function ReviewPage() {
  const search = useSearch({ from: '/app/review' })
  const navigate = useNavigate()
  const month = search.month ?? lastMonth()
  const { data, isPending } = useMonthReview(month)
  const go = (m: string) => void navigate({ to: '/review', search: { month: m } })
  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-5">
      <div className="flex items-center gap-2">
        <Button variant="ghost" size="icon" aria-label="Previous month" onClick={() => go(shift(month, -1))}><ChevronLeft /></Button>
        <h2 className="min-w-0 flex-1 text-center text-2xl font-semibold tracking-tight" data-testid="review-month">{monthName(month)} in review</h2>
        <Button variant="ghost" size="icon" aria-label="Next month" disabled={month >= lastMonth()} onClick={() => go(shift(month, 1))}><ChevronRight /></Button>
      </div>
      {isPending || !data ? <Skeleton className="h-96" /> : data.entries === 0 ? (
        <Card className="p-pad text-center text-sm text-muted">Nothing was recorded in {monthName(month)}.</Card>
      ) : <Review data={data} />}
    </div>
  )
}

function Review({ data }: { data: MonthReview }) {
  const { data: categories = [] } = useCategories()
  const money = (m: number) => formatMoneyWhole(m, data.currency)
  const change = (now: number, before: number) => before > 0 ? Math.round(((now - before) / before) * 100) : null
  const spentChange = change(data.spent, data.spentBefore)
  const max = Math.max(1, ...data.categories.map((c) => Math.max(c.amount, c.before)))
  return (
    <>
      <div className="grid gap-3 sm:grid-cols-3">
        <Stat label="Came in" value={money(data.income)} delta={change(data.income, data.incomeBefore)} good="up" />
        <Stat label="Went out" value={money(data.spent)} delta={spentChange} good="down" />
        <Stat label={data.saved >= 0 ? 'Kept' : 'Overspent'} value={money(Math.abs(data.saved))} icon={<PiggyBank className="size-4" />}
          note={data.income > 0 ? `${Math.round((data.saved / data.income) * 100)}% of income` : undefined} tone={data.saved >= 0 ? 'good' : 'bad'} />
      </div>
      {data.highlights.length > 0 && (
        <Card className="flex flex-col gap-2 border-accent/40 bg-accent-soft p-4" data-testid="review-highlights">
          <p className="flex items-center gap-1.5 text-sm font-medium"><Sparkles className="size-4 text-accent" />Worth knowing</p>
          <ul className="flex flex-col gap-1.5">
            {data.highlights.map((h, i) => (
              <motion.li key={h} initial={{ opacity: 0, x: -6 }} animate={{ opacity: 1, x: 0 }} transition={{ delay: 0.08 * i }} className="text-sm">{h}</motion.li>
            ))}
          </ul>
        </Card>
      )}
      <Card className="flex flex-col gap-3 p-4">
        <p className="text-sm font-medium">Where it went, against the month before</p>
        <ul className="flex flex-col gap-2.5">
          {data.categories.map((c) => {
            const cat = categories.find((x) => x.id === c.categoryId)
            const d = change(c.amount, c.before)
            return (
              <li key={c.categoryId} className="grid grid-cols-[1.5rem_1fr_auto] items-center gap-2 text-sm">
                <CategoryIcon category={cat} className="size-6" />
                <span className="min-w-0">
                  <span className="flex justify-between gap-2"><span className="truncate">{cat?.name ?? 'Category'}</span><span className="tabular">{money(c.amount)}</span></span>
                  <span className="relative mt-1 block h-1.5 rounded-full bg-surface-2">
                    <span className="absolute inset-y-0 left-0 rounded-full bg-border-strong" style={{ width: `${(c.before / max) * 100}%` }} />
                    <span className="absolute inset-y-0 left-0 rounded-full bg-accent" style={{ width: `${(c.amount / max) * 100}%`, opacity: 0.85 }} />
                  </span>
                </span>
                <span className={cn('w-14 text-right text-xs tabular', d === null ? 'text-subtle' : d > 0 ? 'text-critical' : 'text-good-text')}>
                  {d === null ? 'new' : `${d > 0 ? '+' : ''}${d}%`}</span>
              </li>
            )
          })}
        </ul>
        <p className="text-xs text-subtle">The grey bar is the month before.</p>
      </Card>
      <div className="grid gap-3 sm:grid-cols-2">
        <Card className="flex flex-col gap-2 p-4">
          <p className="text-sm font-medium">Biggest spends</p>
          {data.biggest.map((b) => (
            <Link key={b.id} to="/transactions" search={{ q: b.description }} className="flex justify-between gap-2 text-sm hover:underline">
              <span className="truncate">{b.description}</span><span className="tabular">{money(b.amount)}</span>
            </Link>
          ))}
        </Card>
        <Card className="flex flex-col gap-3 p-4">
          <p className="flex items-center gap-2 text-sm"><CalendarOff className="size-4 text-accent" /><b className="tabular">{data.noSpendDays}</b> days with no spending</p>
          {data.budgetsSet > 0 && <p className="flex items-center gap-2 text-sm"><Target className="size-4 text-accent" /><b className="tabular">{data.budgetsKept}/{data.budgetsSet}</b> budgets kept</p>}
          <p className="text-xs text-muted">{data.entries} entries recorded</p>
        </Card>
      </div>
    </>
  )
}

function Stat({ label, value, delta, good, note, icon, tone }: {
  label: string; value: string; delta?: number | null; good?: 'up' | 'down'; note?: string; icon?: React.ReactNode; tone?: 'good' | 'bad'
}) {
  const better = delta == null ? null : good === 'up' ? delta >= 0 : delta <= 0
  return (
    <Card className="flex flex-col gap-1 p-4">
      <span className="flex items-center gap-1.5 text-xs text-muted">{icon}{label}</span>
      <span className={cn('text-2xl font-semibold tabular', tone === 'good' && 'text-good-text', tone === 'bad' && 'text-critical')}>{value}</span>
      {delta != null && (
        <span className={cn('flex items-center gap-0.5 text-xs', better ? 'text-good-text' : 'text-critical')}>
          {delta >= 0 ? <ArrowUpRight className="size-3" /> : <ArrowDownRight className="size-3" />}{Math.abs(delta)}% vs the month before</span>
      )}
      {note && <span className="text-xs text-muted">{note}</span>}
    </Card>
  )
}
