import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronDown, Download, Info } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card, Skeleton } from '@/components/ui/card'
import { SearchSelect } from '@/components/ui/search-select'
import { Progress } from '@/dashboard/charts'
import { api } from '@/lib/api'
import { cn } from '@/lib/cn'
import { categoryTree, useCategories, useSpaceId, type Category } from '@/state/money'
import { CategoryIcon, dayLabel, useMoneyFormat } from './parts'

interface Item { id: string; description: string; amount: number; occurredOn: string; categoryId: string | null; tagged: boolean }
interface Section { code: string; name: string; limit: number | null; covers: string; claimed: number; room: number | null; items: Item[] }
interface Mapping { categoryId: string; section: string }
interface Report { fy: number; from: string; to: string; sections: Section[]; mappings: Mapping[]; suggested: Mapping[] }

function useTax(spaceId: string | undefined, fy: number | undefined) {
  return useQuery({
    queryKey: ['money', spaceId, 'tax', fy ?? 'current'],
    queryFn: () => api<Report>(`/api/core/spaces/${spaceId}/tax${fy ? `?fy=${fy}` : ''}`),
    enabled: !!spaceId,
  })
}

function useMapTax(spaceId: string | undefined) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ categoryId, section }: { categoryId: string; section: string | null }) =>
      api<Mapping[]>(`/api/core/spaces/${spaceId}/tax/mappings/${categoryId}`, { method: 'PUT', json: { section } }),
    onSettled: () => void client.invalidateQueries({ queryKey: ['money', spaceId, 'tax'] }),
  })
}

/**
 * Income-tax deductions for the April–March year: what you paid towards 80C, 80D, HRA and the rest, against each
 * limit, with the entries behind it and a CSV for your CA. A category counts towards a section, and so does any
 * entry tagged with the section's code.
 */
export function TaxPage() {
  const spaceId = useSpaceId()
  const [fy, setFy] = useState<number>()
  const { data, isPending } = useTax(spaceId, fy)
  const { data: categories } = useCategories()
  const map = useMapTax(spaceId)
  const money = useMoneyFormat()
  const [open, setOpen] = useState<string | null>(null)
  const current = data?.fy ?? new Date().getFullYear()
  const years = [current - 1, current, current + 1].filter((y) => y <= new Date().getFullYear())

  const spending = (categories ?? []).filter((c) => c.kind === 'EXPENSE' && !c.hidden)
  const sectionOf = (id: string) => data?.mappings.find((m) => m.categoryId === id)?.section ?? ''
  const name = (id: string | null) => (categories ?? []).find((c) => c.id === id)?.name ?? ''

  async function applySuggested() {
    for (const s of data?.suggested ?? []) await map.mutateAsync(s)
    toast.success('Sections set', { description: 'Change any of them below.' })
  }

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <p className="max-w-xl text-sm text-muted">
          What you paid towards each deduction this financial year. Tag an entry <code className="rounded bg-surface-2 px-1">80C</code> to
          count it, or set a section on a whole category below. <Link to="/reports" className="underline underline-offset-4">All reports</Link>
        </p>
        <div className="flex items-center gap-2">
          <select aria-label="Financial year" value={data?.fy ?? ''} onChange={(e) => setFy(Number(e.target.value))}
            className="h-9 rounded-control border border-border-strong bg-surface px-2 text-sm">
            {years.map((y) => <option key={y} value={y}>FY {y}–{String(y + 1).slice(2)}</option>)}
          </select>
          {spaceId && data && (
            <Button asChild size="sm"><a href={`/api/core/spaces/${spaceId}/tax/export?fy=${data.fy}`} download><Download />CSV for your CA</a></Button>
          )}
        </div>
      </div>

      <p className="flex items-start gap-2 rounded-control bg-surface-2 px-3 py-2 text-xs text-muted">
        <Info className="mt-0.5 size-3.5 shrink-0" />
        These deductions apply under the old tax regime; the new regime (the default) allows almost none of them. Limits are per person for FY 2025–26 onwards. Not tax advice: check with your CA.
      </p>

      {isPending && <Skeleton className="h-64" />}
      {data && data.suggested.length > 0 && (
        <Card className="flex flex-wrap items-center justify-between gap-3 p-pad">
          <p className="text-sm">
            {data.suggested.map((s) => `${name(s.categoryId)} → ${data.sections.find((x) => x.code === s.section)?.name}`).join(', ')}?
          </p>
          <Button size="sm" variant="primary" onClick={() => void applySuggested()} disabled={map.isPending}>Use these</Button>
        </Card>
      )}

      {data && (
        <Card className="divide-y divide-border">
          {data.sections.map((s) => (
            <div key={s.code} data-testid={`tax-${s.code}`}>
              <button type="button" onClick={() => setOpen(open === s.code ? null : s.code)} aria-expanded={open === s.code}
                className="flex w-full items-center gap-4 px-pad py-3 text-left hover:bg-surface-2">
                <span className="w-24 shrink-0 font-semibold">{s.name}</span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-xs text-muted">{s.covers}</span>
                  {s.limit !== null && <span className="mt-1.5 block"><Progress value={Math.min(1, s.claimed / s.limit)} label={`${s.name} used`}
                    color={s.claimed >= s.limit ? 'var(--good)' : undefined} /></span>}
                </span>
                <span className="text-right text-sm">
                  <span className="block font-medium tabular">{money(s.claimed)}</span>
                  <span className="block text-xs text-subtle">
                    {s.limit === null ? `${s.items.length} ${s.items.length === 1 ? 'entry' : 'entries'}` : s.room === 0 ? 'limit reached' : `${money(s.room ?? 0)} room of ${money(s.limit)}`}
                  </span>
                </span>
                <ChevronDown className={cn('size-4 shrink-0 text-subtle transition-transform', open === s.code && 'rotate-180')} />
              </button>
              {open === s.code && (
                <ul className="bg-surface-2/50 px-pad pb-3">
                  {s.items.length === 0 && <li className="py-2 text-sm text-muted">Nothing yet this year.</li>}
                  {s.items.map((i) => (
                    <li key={i.id} className="flex items-center justify-between gap-3 py-1.5 text-sm">
                      <span className="min-w-0 truncate">{i.description}<span className="ml-2 text-xs text-subtle">{dayLabel(i.occurredOn)} · {i.tagged ? 'tagged' : name(i.categoryId)}</span></span>
                      <span className="tabular">{money(i.amount)}</span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          ))}
        </Card>
      )}

      {data && categories && (
        <section className="flex flex-col gap-3">
          <h3 className="text-base font-semibold">Categories that count</h3>
          <Card className="divide-y divide-border">
            {categoryTree(spending).flatMap(({ category, children }) => [category, ...children]).map((c: Category) => (
              <div key={c.id} className="flex items-center gap-3 px-pad py-2">
                <CategoryIcon category={c} />
                <span className={cn('min-w-0 flex-1 truncate text-sm', c.parentId && 'pl-4')}>{c.name}</span>
                <SearchSelect aria-label={`Tax section for ${c.name}`} className="w-44" value={sectionOf(c.id)}
                  onChange={(v) => map.mutate({ categoryId: c.id, section: v || null })}
                  options={[{ value: '', label: 'None' }, ...data.sections.map((s) => ({ value: s.code, label: s.name, keywords: s.covers }))]} />
              </div>
            ))}
          </Card>
        </section>
      )}
    </div>
  )
}
