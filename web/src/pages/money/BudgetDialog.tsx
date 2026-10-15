import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { MoneyInput } from '@/components/ui/money-input'
import { CategoryPicker } from '@/pages/money/pickers'
import { Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Switch } from '@/components/ui/controls'
import { Dialog } from '@/components/ui/overlay'
import { formatMoneyWhole, minorToInput, parseMoney } from '@/lib/money'
import {
  categoryTree, shiftMonth, useCategories, useCategoryTotals, useRemoveBudget, useSetBudget,
  type Budget, type BudgetMonth,
} from '@/state/money'
import { useProfile } from '@/state/profile'
import { Field, categoryOptions } from './parts'

/** Set a monthly limit for a category, change it, or stop budgeting it, from the month on screen onwards. */
export function BudgetDialog({ open, onOpenChange, month, budget }: {
  open: boolean
  onOpenChange: (open: boolean) => void
  month: BudgetMonth
  budget?: Budget
}) {
  const { data: categories = [] } = useCategories()
  const locale = useProfile().data?.settings.locale ?? 'en-IN'
  const set = useSetBudget()
  const remove = useRemoveBudget()
  const [categoryId, setCategoryId] = useState('')
  const [amount, setAmount] = useState('')
  const [rollover, setRollover] = useState(false)
  const [error, setError] = useState<string>()

  useEffect(() => {
    if (!open) return
    setCategoryId(budget?.categoryId ?? '')
    setAmount(budget ? minorToInput(budget.limit, budget.currency) : '')
    setRollover(budget?.rollover ?? false)
    setError(undefined)
  }, [open, budget])

  // Spending categories that don't have a budget this month yet.
  const choices = useMemo(() => {
    const taken = new Set(month.budgets.map((b) => b.categoryId))
    const visible = categories.filter((c) => c.kind === 'EXPENSE' && !c.hidden)
    return categoryTree(visible).flatMap(({ category, children }) => [category, ...children]).filter((c) => !taken.has(c.id))
  }, [categories, month.budgets])

  // A starting point instead of an empty box: the average of the three months before this one.
  const before = useCategoryTotals('EXPENSE', periodStart(month, -3), dayBefore(month.start), false, open)
  const chosen = budget?.categoryId ?? categoryId
  const average = useMemo(() => {
    if (!chosen || !before.data) return null
    const ids = new Set([chosen, ...categories.filter((c) => c.parentId === chosen).map((c) => c.id)])
    const total = before.data.filter((t) => ids.has(t.categoryId)).reduce((sum, t) => sum + t.amount, 0)
    return Math.round(total / 3)
  }, [chosen, before.data, categories])

  const name = categories.find((c) => c.id === chosen)?.name ?? 'this category'
  const monthName = monthLabel(month.month, locale)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(undefined)
    if (!chosen) return setError('Choose a category.')
    const limit = parseMoney(amount)
    if (limit === null || limit <= 0) return setError('Enter a monthly limit, like 12,000.')
    try {
      await set.mutateAsync({ categoryId: chosen, amount: limit, rollover, from: month.month })
      toast.success(budget ? 'Budget changed' : `Budget set for ${name}`, { description: `From ${monthName} on.` })
      onOpenChange(false)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not save. Try again.')
    }
  }

  async function stop() {
    if (!budget) return
    try {
      await remove.mutateAsync({ categoryId: budget.categoryId, from: month.month })
      toast(`No budget for ${name} from ${monthName}`, { description: 'Earlier months keep theirs.' })
      onOpenChange(false)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not save. Try again.')
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={budget ? `${name} budget` : 'Set a budget'}
      description={`Applies from ${monthName} on; earlier months keep theirs.`}>
      <form onSubmit={submit} className="flex flex-col gap-4 p-pad" noValidate>
        {!budget && (
          <Field label="Category" htmlFor="budget-category">
            <CategoryPicker kind="EXPENSE" id="budget-category" value={categoryId} onChange={setCategoryId} options={categoryOptions(choices, categories)} />
          </Field>
        )}
        <Field label="Monthly limit" htmlFor="budget-amount"
          hint={average ? `You spent about ${formatMoneyWhole(average, undefined, locale)} a month on it over the last three months.` : undefined}>
          <MoneyInput id="budget-amount" autoComplete="off" placeholder="12,000" value={amount} autoFocus={!!budget}
            onChange={(e) => setAmount(e.target.value)} className="tabular" />
        </Field>
        {average !== null && average > 0 && amount.trim() === '' && (
          <Button variant="outline" size="sm" className="self-start" onClick={() => setAmount(minorToInput(roundUp(average)))}>
            Use {formatMoneyWhole(roundUp(average), undefined, locale)}
          </Button>
        )}
        <div className="flex items-start gap-3 text-sm">
          <Switch checked={rollover} onCheckedChange={setRollover} label="Roll over" id="budget-rollover" />
          <div>
            <span className="block font-medium">Roll over</span>
            <span className="block text-xs text-subtle">What isn't spent carries into next month; an overspend comes off it. Good for things like clothes or gifts.</span>
          </div>
        </div>
        {error && <p className="rounded-control bg-critical/10 px-3 py-2 text-sm text-critical" role="alert">{error}</p>}
        <div className="flex items-center gap-2">
          {budget && (
            <Button variant="ghost" onClick={stop} disabled={remove.isPending}><Trash2 />Stop budgeting</Button>
          )}
          <span className="flex-1" />
          <Button variant="ghost" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button type="submit" variant="primary" disabled={set.isPending}>{budget ? 'Save' : 'Set budget'}</Button>
        </div>
      </form>
    </Dialog>
  )
}

/** "2026-10" -> "October 2026". */
export function monthLabel(month: string, locale: string): string {
  const [y, m] = month.split('-').map(Number)
  return new Date(y!, m! - 1, 1).toLocaleDateString(locale, { month: 'long', year: 'numeric' })
}

/** Start date of the period `by` months from this one (same start day). */
function periodStart(month: BudgetMonth, by: number): string {
  return `${shiftMonth(month.month, by)}-${month.start.slice(8)}`
}

function dayBefore(iso: string): string {
  const [y, m, d] = iso.split('-').map(Number)
  const date = new Date(y!, m! - 1, d! - 1)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

/** A suggestion people would type: up to the next ₹500. */
function roundUp(minor: number): number {
  return Math.ceil(minor / 50_000) * 50_000
}
