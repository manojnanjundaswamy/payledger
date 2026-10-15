import { Link } from '@tanstack/react-router'
import { useState } from 'react'
import { Plus, Search, TrendingDown, TrendingUp } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, Skeleton } from '@/components/ui/card'
import { Input, Segmented } from '@/components/ui/controls'
import { cn } from '@/lib/cn'
import { formatMoneyShort } from '@/lib/money'
import { reportPeriod } from '@/lib/periods'
import { categoryTree, useBreakdown, useBudgetMonths, useCategories, useCategoryTotals, type Category } from '@/state/money'
import { useProfile } from '@/state/profile'
import { CategoryDialog } from './CategoryDialog'
import { CategoryRules } from './CategoryRules'
import { CategoryIcon, EditButton, useMoneyFormat } from './parts'

type Kind = Category['kind']

/** Spending and income categories, with subcategories under their parent. Hidden ones are listed last. */
export function CategoriesPage() {
  const { data: categories = [], isPending, isError } = useCategories()
  const [kind, setKind] = useState<Kind>('EXPENSE')
  const [editing, setEditing] = useState<Category | 'new' | null>(null)

  const [q, setQ] = useState('')
  const ofKind = categories.filter((c) => c.kind === kind)
  // A search keeps a parent when it or any of its subcategories match, so the tree still reads.
  const needle = q.trim().toLowerCase()
  const hits = (c: Category) => c.name.toLowerCase().includes(needle)
  const matches = (list: ReturnType<typeof categoryTree>) => !needle ? list
    : list.filter((n) => hits(n.category) || n.children.some(hits)).map((n) => ({ ...n, children: hits(n.category) ? n.children : n.children.filter(hits) }))
  const shown = matches(categoryTree(ofKind.filter((c) => !c.hidden)))
  // A hidden parent carries its (also hidden) subcategories; a hidden subcategory of a shown parent stands alone.
  const hidden = matches(categoryTree(ofKind.filter((c) => c.hidden)))
  const stats = useCategoryStats(kind, categories)
  const money = useMoneyFormat()
  const top = shown.map((n) => ({ c: n.category, s: stats.of(n.category) })).sort((a, b) => b.s.now - a.s.now)
  const total = top.reduce((sum, t) => sum + t.s.now, 0)
  const unbudgeted = kind === 'EXPENSE' ? top.filter((t) => t.s.now > 0 && t.s.budget === null && !t.c.parentId).slice(0, 3) : []

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <Segmented label="Kind" value={kind} onChange={setKind}
          options={[{ value: 'EXPENSE', label: 'Spending' }, { value: 'INCOME', label: 'Income' }]} />
        <label className="relative min-w-0 flex-1 basis-40">
          <span className="sr-only">Search categories</span>
          <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-subtle" />
          <Input type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search categories" className="pl-9" />
        </label>
        <Button variant="primary" onClick={() => setEditing('new')}><Plus />Add category</Button>
      </div>
      {!isPending && total > 0 && (
        <Card className="flex flex-wrap items-center gap-x-6 gap-y-2 p-pad text-sm" data-testid="categories-summary">
          <span><span className="text-muted">{kind === 'EXPENSE' ? 'Spent this month' : 'Earned this month'}: </span>
            <span className="font-semibold tabular">{money(total)}</span></span>
          {top[0] && top[0].s.now > 0 && (
            <span><span className="text-muted">Most: </span>
              <Link to="/transactions" search={{ category: top[0].c.id, from: stats.period.from, to: stats.period.to }} className="font-medium hover:underline">
                {top[0].c.name} {Math.round((top[0].s.now / total) * 100)}%</Link></span>
          )}
          {unbudgeted.length > 0 && (
            <span className="text-muted">No budget yet for {unbudgeted.map((u) => u.c.name).join(', ')}.{' '}
              <Link to="/budgets" className="text-fg underline underline-offset-4">Set one</Link></span>
          )}
        </Card>
      )}
      {needle && shown.length === 0 && hidden.length === 0 && <Card className="p-pad text-sm text-muted">No {kind === 'EXPENSE' ? 'spending' : 'income'} category matches "{q.trim()}".</Card>}

      {isError && <Card className="p-pad text-sm text-critical">Couldn't load your categories. Refresh to try again.</Card>}
      {isPending && <Skeleton className="h-64" />}

      {!isPending && (
        <section aria-label={kind === 'EXPENSE' ? 'Spending categories' : 'Income categories'}>
          <Card className="divide-y divide-border p-0">
            {shown.map(({ category, children }) => (
              <div key={category.id}>
                <Row category={category} onEdit={setEditing} stats={stats.of(category)} period={stats.period} />
                {children.map((child) => <Row key={child.id} category={child} onEdit={setEditing} sub stats={stats.of(child)} period={stats.period} />)}
              </div>
            ))}
          </Card>
        </section>
      )}

      {hidden.length > 0 && (
        <section aria-label="Hidden" className="flex flex-col gap-2">
          <h2 className="text-sm font-medium text-muted">Hidden</h2>
          <p className="text-xs text-subtle">Not offered for new transactions. Their history stays in your reports.</p>
          <Card className="divide-y divide-border p-0">
            {hidden.map(({ category, children }) => (
              <div key={category.id}>
                <Row category={category} onEdit={setEditing} parent={categories.find((p) => p.id === category.parentId)} />
                {children.map((child) => <Row key={child.id} category={child} onEdit={setEditing} sub />)}
              </div>
            ))}
          </Card>
        </section>
      )}

      {!isPending && <CategoryRules categories={categories} />}

      <CategoryDialog open={editing !== null} onOpenChange={(o) => !o && setEditing(null)} kind={kind}
        category={editing === 'new' || editing === null ? undefined : editing} />
    </div>
  )
}

interface Stats {
  /** This (budget) month and last, paise; a parent includes its subcategories. */
  now: number
  last: number
  count: number
  /** Six calendar months, oldest first. */
  trend: number[]
  budget: { limit: number; spent: number | null } | null
}

/**
 * Each category's numbers for its row: this month against last, how many entries, six months of it, and its budget.
 * A parent counts its subcategories, as reports and budgets do.
 */
function useCategoryStats(kind: Category['kind'], categories: Category[]) {
  const startDay = useProfile().data?.settings.budgetStartDay ?? 1
  const period = reportPeriod('this-month', startDay)
  const previous = reportPeriod('last-month', startDay)
  const now = useBreakdown({ from: period.from, to: period.to })
  const last = useBreakdown({ from: previous.from, to: previous.to })
  const today = new Date()
  const months = Array.from({ length: 6 }, (_, i) => {
    const d = new Date(today.getFullYear(), today.getMonth() - 5 + i, 1)
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
  })
  const sixFrom = `${months[0]}-01`
  const trend = useCategoryTotals(kind, sixFrom, period.to, true)
  const [budgetMonth] = useBudgetMonths([period.from.slice(0, 7)])
  const family = (c: Category) => new Set([c.id, ...categories.filter((x) => x.parentId === c.id).map((x) => x.id)])
  const sum = (rows: { categoryId: string | null; amount: number; kind?: string }[] | undefined, ids: Set<string>) =>
    (rows ?? []).filter((r) => r.categoryId && ids.has(r.categoryId) && (!r.kind || r.kind === kind)).reduce((s, r) => s + r.amount, 0)
  return {
    period,
    of(c: Category): Stats {
      const ids = family(c)
      const b = budgetMonth?.data?.budgets.find((x) => x.categoryId === c.id)
      return {
        now: sum(now.data?.byCategory, ids),
        last: sum(last.data?.byCategory, ids),
        count: (now.data?.byCategory ?? []).filter((r) => r.categoryId && ids.has(r.categoryId) && r.kind === kind).reduce((s, r) => s + r.count, 0),
        trend: months.map((m) => (trend.data ?? []).filter((r) => ids.has(r.categoryId) && r.month?.startsWith(m)).reduce((s, r) => s + r.amount, 0)),
        budget: b ? { limit: b.limit, spent: b.spent } : null,
      }
    },
  }
}

function Row({ category, onEdit, sub, parent, stats, period }: {
  category: Category; onEdit: (c: Category) => void; sub?: boolean; parent?: Category; stats?: Stats; period?: { from: string; to: string }
}) {
  const locale = useProfile().data?.settings.locale ?? 'en-IN'
  const short = (m: number) => formatMoneyShort(m, undefined, locale)
  const max = Math.max(1, ...(stats?.trend ?? [0]))
  const change = stats && stats.last > 0 ? Math.round(((stats.now - stats.last) / stats.last) * 100) : null
  const spending = category.kind === 'EXPENSE'
  const used = stats?.budget && stats.budget.spent !== null ? stats.budget.spent / Math.max(stats.budget.limit, 1) : null
  return (
    <div className="flex items-center gap-1 pr-2 hover:bg-surface-2" data-testid={`category-${category.name}`}>
    <Link to="/transactions" search={period ? { category: category.id, from: period.from, to: period.to } : { category: category.id }} aria-label={`${category.name} transactions`}
      className={cn('flex min-w-0 flex-1 items-center gap-3 py-2.5 pl-pad text-left', sub && 'pl-[calc(var(--pad)+2.75rem)]')}>
      <CategoryIcon category={category} className={cn(sub && 'size-7')} />
      <span className="min-w-0 flex-1">
        <span className={cn('block truncate', !sub && 'font-medium')}>{category.name}</span>
        {parent && <span className="block text-xs text-subtle">in {parent.name}</span>}
        {stats && stats.budget && (
          <span className="mt-1 flex items-center gap-2 text-xs text-subtle">
            <span className="h-1.5 w-24 overflow-hidden rounded-full bg-surface-2">
              <span className={cn('block h-full rounded-full', used !== null && used > 1 ? 'bg-critical' : used !== null && used >= 0.8 ? 'bg-warning' : 'bg-[var(--flow-in)]')}
                style={{ width: `${Math.min(1, used ?? 0) * 100}%` }} />
            </span>
            <span className="tabular">{stats.budget.spent === null ? '—' : short(stats.budget.spent)} of {short(stats.budget.limit)}</span>
          </span>
        )}
      </span>
      {stats && (
        <>
          <span className="hidden h-6 items-end gap-0.5 sm:flex" aria-hidden title="Last six months">
            {stats.trend.map((v, i) => (
              <span key={i} className={cn('w-1.5 rounded-t-sm', i === stats.trend.length - 1 ? 'bg-[var(--flow-out)]' : 'bg-[var(--flow-out)]/40')}
                style={{ height: `${Math.max(v > 0 ? 8 : 0, (v / max) * 100)}%` }} />
            ))}
          </span>
          <span className="w-28 shrink-0 text-right">
            <span className="block text-sm font-medium tabular" data-testid="category-month">{stats.now > 0 ? short(stats.now) : '—'}</span>
            <span className="flex items-center justify-end gap-1 text-xs text-subtle">
              {stats.count > 0 && <span>{stats.count}×</span>}
              {change !== null && change !== 0 && (
                <span className={cn('flex items-center gap-0.5', spending ? (change > 0 ? 'text-critical' : 'text-good-text') : (change > 0 ? 'text-good-text' : 'text-critical'))}
                  title="Against last month">
                  {change > 0 ? <TrendingUp className="size-3" /> : <TrendingDown className="size-3" />}{Math.abs(change)}%
                </span>
              )}
            </span>
          </span>
        </>
      )}
    </Link>
    <EditButton label={`Edit ${category.name}`} onClick={() => onEdit(category)} />
    </div>
  )
}
