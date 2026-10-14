import { useState } from 'react'
import { Plus, Sparkles, X } from 'lucide-react'
import { useQuery } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { api } from '@/lib/api'
import { useCategories, useCreateCommitment, useSpaceId } from '@/state/money'

interface Suggestion {
  name: string
  amount: number
  accountId: string
  categoryId: string
  dayOfMonth: number
  months: number
  lastPaid: string
  nextDue: string
}

const DISMISSED = 'dismissed-bill-suggestions'

function dismissed(): string[] {
  try { return JSON.parse(localStorage.getItem(DISMISSED) ?? '[]') as string[] } catch { return [] }
}

/**
 * Expenses that come back every month at a steady amount (from the market scan: Monarch finds recurring
 * charges), offered for the plan in one tap so bills don't have to be typed in twice.
 */
export function BillSuggestions({ money }: { money: (m: number) => string }) {
  const spaceId = useSpaceId()
  const { data = [] } = useQuery({
    queryKey: ['money', spaceId, 'commitment-suggestions'],
    queryFn: () => api<Suggestion[]>(`/api/core/spaces/${spaceId}/commitments/suggestions`),
    enabled: !!spaceId,
  })
  const { data: categories = [] } = useCategories()
  const create = useCreateCommitment()
  const [hidden, setHidden] = useState<string[]>(dismissed)
  const shown = data.filter((s) => !hidden.includes(s.name.toLowerCase()))
  if (shown.length === 0) return null

  function dismiss(name: string) {
    const next = [...hidden, name.toLowerCase()]
    setHidden(next)
    try { localStorage.setItem(DISMISSED, JSON.stringify(next)) } catch { /* a private window: it may come back */ }
  }

  async function add(s: Suggestion) {
    await create.mutateAsync({
      kind: 'BILL', name: s.name, amount: s.amount, accountId: s.accountId, categoryId: s.categoryId, toAccountId: null,
      everyMonths: 1, firstDue: s.nextDue, endsOn: null,
    })
    toast.success(`${s.name} added to your plan`, { description: 'Change the day or amount any time.' })
  }

  return (
    <section aria-label="Looks like a regular bill" className="flex flex-col gap-2">
      <h2 className="flex items-center gap-1.5 text-sm font-medium text-muted"><Sparkles className="size-4" />Looks like a regular bill</h2>
      <Card className="divide-y divide-border p-0">
        {shown.map((s) => (
          <div key={s.name} className="flex flex-wrap items-center gap-3 px-pad py-3" data-testid={`suggestion-${s.name}`}>
            <span className="min-w-0 flex-1 max-sm:basis-full">
              <span className="block truncate font-medium">{s.name}</span>
              <span className="block text-xs text-subtle">
                {money(s.amount)} around the {ordinal(s.dayOfMonth)} · {categories.find((c) => c.id === s.categoryId)?.name ?? 'Category'} · seen in {s.months} months
              </span>
            </span>
            <span className="ml-auto flex items-center gap-1">
              <Button size="sm" variant="ghost" onClick={() => dismiss(s.name)} aria-label={`${s.name} is not a bill`}><X />Not a bill</Button>
              <Button size="sm" variant="outline" onClick={() => add(s)} disabled={create.isPending}><Plus />Add to plan</Button>
            </span>
          </div>
        ))}
      </Card>
    </section>
  )
}

function ordinal(day: number): string {
  const suffix = day % 10 === 1 && day !== 11 ? 'st' : day % 10 === 2 && day !== 12 ? 'nd' : day % 10 === 3 && day !== 13 ? 'rd' : 'th'
  return `${day}${suffix}`
}
