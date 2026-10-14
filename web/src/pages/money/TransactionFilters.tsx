import { useEffect, useState } from 'react'
import { Download, Search, SlidersHorizontal, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input, Segmented } from '@/components/ui/controls'
import { cn } from '@/lib/cn'
import { PERIOD_KEYS, reportPeriod, type PeriodKey } from '@/lib/periods'
import { exportUrl, useSpaceId, useTransactionSummary, type Account, type Category, type TransactionFilter, type TxnKind } from '@/state/money'
import { useProfile } from '@/state/profile'
import { Select, dayLabel, useMoneyFormat } from './parts'

/** The list's search, as it sits in the URL (amounts in rupees, so a link reads naturally). */
export interface FilterSearch {
  q?: string
  kind?: TxnKind
  account?: string
  category?: string
  from?: string
  to?: string
  min?: number
  max?: number
  tag?: string
}

export const FILTER_KEYS = ['q', 'kind', 'account', 'category', 'from', 'to', 'min', 'max', 'tag'] as const

/** The URL's search as the API's filter. */
export function toFilter(s: FilterSearch): TransactionFilter {
  return {
    q: s.q, kind: s.kind, accountId: s.account, categoryId: s.category, from: s.from, to: s.to, tag: s.tag,
    minAmount: s.min === undefined ? undefined : Math.round(s.min * 100),
    maxAmount: s.max === undefined ? undefined : Math.round(s.max * 100),
  }
}

const PRESETS: Record<PeriodKey, string> = {
  'this-month': 'This month', 'last-month': 'Last month', 'this-fy': 'This financial year', 'last-fy': 'Last financial year',
  'last-12': 'Last 12 months', custom: 'Custom',
}

/**
 * Search and filters for transactions: words or an amount in one box, and a panel for type, account, category, dates,
 * amount range and tag. Every filter shows as a chip that removes it, and the totals and exports follow the filter.
 */
export function TransactionFilters({ search, onChange, accounts, categories }: {
  search: FilterSearch
  onChange: (next: Partial<FilterSearch>) => void
  accounts: Account[]
  categories: Category[]
}) {
  const locale = useProfile().data?.settings.locale ?? 'en-IN'
  const startDay = useProfile().data?.settings.budgetStartDay ?? 1
  const money = useMoneyFormat()
  const spaceId = useSpaceId()
  const [text, setText] = useState(search.q ?? '')
  const active = FILTER_KEYS.filter((k) => search[k] !== undefined && search[k] !== '')
  const [open, setOpen] = useState(false)
  const filter = toFilter(search)
  const summary = useTransactionSummary(filter, active.length > 0)

  // Type to search: the URL follows a moment after the last key, so typing doesn't flood the history or the server.
  useEffect(() => setText(search.q ?? ''), [search.q])
  useEffect(() => {
    const next = text.trim() || undefined
    if (next === search.q) return
    const t = setTimeout(() => onChange({ q: next }), 300)
    return () => clearTimeout(t)
  }, [text]) // eslint-disable-line react-hooks/exhaustive-deps

  const categoryName = (id: string) => {
    const c = categories.find((x) => x.id === id)
    const parent = c?.parentId ? categories.find((x) => x.id === c.parentId) : undefined
    return c ? (parent ? `${parent.name} › ${c.name}` : c.name) : 'Category'
  }
  const rupees = (v: number) => money(Math.round(v * 100)).replace(/\.00$/, '')
  const chips: { key: (typeof FILTER_KEYS)[number][]; label: string }[] = []
  if (search.kind) chips.push({ key: ['kind'], label: search.kind === 'EXPENSE' ? 'Expenses' : search.kind === 'INCOME' ? 'Income' : 'Transfers' })
  if (search.category) chips.push({ key: ['category'], label: categoryName(search.category) })
  if (search.from || search.to) {
    chips.push({ key: ['from', 'to'], label: search.from && search.to ? `${dayLabel(search.from, locale)} – ${dayLabel(search.to, locale)}` : search.from ? `From ${dayLabel(search.from, locale)}` : `Until ${dayLabel(search.to!, locale)}` })
  }
  if (search.min !== undefined || search.max !== undefined) {
    chips.push({ key: ['min', 'max'], label: search.min !== undefined && search.max !== undefined ? `${rupees(search.min)} – ${rupees(search.max)}` : search.min !== undefined ? `${rupees(search.min)} or more` : `Up to ${rupees(search.max!)}` })
  }
  if (search.tag) chips.push({ key: ['tag'], label: `#${search.tag}` })
  const clearAll = () => {
    setText('')
    onChange(Object.fromEntries(FILTER_KEYS.map((k) => [k, undefined])))
  }

  const parents = categories.filter((c) => !c.parentId && !c.hidden && (!search.kind || search.kind === 'TRANSFER' || c.kind === search.kind))
  const total = summary.data?.totals[0]

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <label className="relative min-w-0 flex-1 basis-56">
          <span className="sr-only">Search transactions</span>
          <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-subtle" />
          <Input type="search" value={text} onChange={(e) => setText(e.target.value)} placeholder="Search: Swiggy, rent, 450…"
            className="pl-9" maxLength={100} data-testid="txn-search" />
        </label>
        <Select aria-label="Filter by account" value={search.account ?? ''} className="w-auto max-w-56 min-w-40 flex-none"
          onChange={(e) => onChange({ account: e.target.value || undefined })}>
          <option value="">All accounts</option>
          {accounts.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
        </Select>
        <Button variant={open || chips.length > 0 ? 'outline' : 'ghost'} onClick={() => setOpen((o) => !o)} aria-expanded={open} aria-controls="txn-filters">
          <SlidersHorizontal />Filters{chips.length > 0 && <span className="rounded-full bg-accent-soft px-1.5 text-xs tabular">{chips.length}</span>}
        </Button>
      </div>

      {open && (
        <Card id="txn-filters" className="grid gap-4 p-pad sm:grid-cols-2" role="group" aria-label="Filters">
          <div className="flex flex-col gap-1.5 sm:col-span-2">
            <span className="text-sm font-medium">Type</span>
            <Segmented label="Type" value={search.kind ?? 'ALL'} className="self-start"
              onChange={(v) => onChange({ kind: v === 'ALL' ? undefined : (v as TxnKind) })}
              options={[{ value: 'ALL', label: 'All' }, { value: 'EXPENSE', label: 'Expenses' }, { value: 'INCOME', label: 'Income' }, { value: 'TRANSFER', label: 'Transfers' }]} />
          </div>
          <label className="flex flex-col gap-1.5 text-sm font-medium">Category
            <Select value={search.category ?? ''} onChange={(e) => onChange({ category: e.target.value || undefined })} disabled={search.kind === 'TRANSFER'}>
              <option value="">All categories</option>
              {parents.map((p) => (
                <optgroup key={p.id} label={p.name}>
                  <option value={p.id}>{p.name} (all)</option>
                  {categories.filter((c) => c.parentId === p.id && !c.hidden).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
                </optgroup>
              ))}
            </Select>
          </label>
          <label className="flex flex-col gap-1.5 text-sm font-medium">Dates
            <Select value="" onChange={(e) => {
              if (!e.target.value) return
              const p = reportPeriod(e.target.value as PeriodKey, startDay)
              onChange({ from: p.from, to: p.to })
            }}>
              <option value="">Pick a period…</option>
              {PERIOD_KEYS.map((k) => <option key={k} value={k}>{PRESETS[k]}</option>)}
            </Select>
          </label>
          <div className="grid grid-cols-2 gap-2">
            <label className="flex flex-col gap-1.5 text-sm font-medium">From
              <Input type="date" value={search.from ?? ''} max={search.to} onChange={(e) => onChange({ from: e.target.value || undefined })} />
            </label>
            <label className="flex flex-col gap-1.5 text-sm font-medium">To
              <Input type="date" value={search.to ?? ''} min={search.from} onChange={(e) => onChange({ to: e.target.value || undefined })} />
            </label>
          </div>
          <div className="grid grid-cols-2 gap-2">
            <label className="flex flex-col gap-1.5 text-sm font-medium">Amount from (₹)
              <AmountInput value={search.min} onChange={(v) => onChange({ min: v })} placeholder="0" />
            </label>
            <label className="flex flex-col gap-1.5 text-sm font-medium">Amount to (₹)
              <AmountInput value={search.max} onChange={(v) => onChange({ max: v })} placeholder="Any" />
            </label>
          </div>
          <label className="flex flex-col gap-1.5 text-sm font-medium">Tag
            <Input value={search.tag ?? ''} maxLength={40} placeholder="trip" onChange={(e) => onChange({ tag: e.target.value.replace(/^#/, '') || undefined })} />
          </label>
        </Card>
      )}

      {active.length > 0 && (
        <div className="flex flex-wrap items-center gap-2 text-sm" data-testid="txn-filter-summary">
          {chips.map((c) => (
            <span key={c.label} className="inline-flex items-center gap-1 rounded-full border border-border bg-surface-2 py-0.5 pr-0.5 pl-3 text-xs" data-testid="filter-chip">
              {c.label}
              <button type="button" aria-label={`Remove filter ${c.label}`} className="rounded-full p-0.5 text-muted hover:bg-surface-3 hover:text-fg"
                onClick={() => onChange(Object.fromEntries(c.key.map((k) => [k, undefined])))}>
                <X className="size-3.5" />
              </button>
            </span>
          ))}
          <button type="button" onClick={clearAll} className="text-xs text-muted underline-offset-4 hover:text-fg hover:underline">Clear all</button>
          <span className="flex-1" />
          <span className={cn('text-xs text-muted tabular', summary.isPending && 'opacity-50')} data-testid="txn-found">
            {!total ? (summary.isPending ? 'Counting…' : 'Nothing found') : (
              <>
                {total.count} found
                {total.spent > 0 && <> · <span className="text-fg">{money(total.spent, total.currency)}</span> spent</>}
                {total.earned > 0 && <> · <span className="text-good-text">{money(total.earned, total.currency)}</span> in</>}
              </>
            )}
          </span>
          {spaceId && total && (
            <span className="flex gap-1">
              <Button asChild size="sm" variant="ghost"><a href={exportUrl(spaceId, 'csv', filter)} download aria-label="Download these as CSV"><Download />CSV</a></Button>
              <Button asChild size="sm" variant="ghost"><a href={exportUrl(spaceId, 'xlsx', filter)} download aria-label="Download these as Excel"><Download />Excel</a></Button>
            </span>
          )}
        </div>
      )}
    </div>
  )
}

/** Rupees, committed on blur or Enter so half-typed numbers don't refilter the list. */
function AmountInput({ value, onChange, placeholder }: { value?: number; onChange: (v?: number) => void; placeholder: string }) {
  const [text, setText] = useState(value === undefined ? '' : String(value))
  useEffect(() => setText(value === undefined ? '' : String(value)), [value])
  const commit = () => {
    const n = Number(text.replace(/[,₹\s]/g, ''))
    onChange(text.trim() === '' || !Number.isFinite(n) || n < 0 ? undefined : n)
  }
  return <Input inputMode="decimal" value={text} placeholder={placeholder} onChange={(e) => setText(e.target.value)} onBlur={commit}
    onKeyDown={(e) => e.key === 'Enter' && commit()} />
}
