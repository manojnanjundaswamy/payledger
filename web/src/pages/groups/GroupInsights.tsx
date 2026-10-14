import { BarChart3 } from 'lucide-react'
import { Card, Skeleton } from '@/components/ui/card'
import { BarList, Donut, GroupedBars } from '@/dashboard/charts'
import { formatMoney, formatMoneyShort, formatMoneyWhole } from '@/lib/money'
import { useInsights, type Group } from '@/state/groups'

/**
 * A group in numbers: what was spent and your part of it, who paid against whose share it was, what it went on and
 * when, and the biggest expenses. Each chart opens what's behind it (a member's or label's expenses, a month on the
 * calendar, an expense).
 */
export function GroupInsights({ group, locale, onLabel, onMonth, onExpense, onMember }: {
  group: Group
  locale: string
  onLabel: (label: string | null) => void
  onMonth: (month: string) => void
  onExpense: (id: string) => void
  onMember: (memberId: string) => void
}) {
  const { data, isPending, isError } = useInsights(group.id)
  const money = (m: number) => formatMoneyWhole(m, group.currency, locale)
  const short = (m: number) => formatMoneyShort(m, group.currency, locale)
  if (isError) return <Card className="p-pad text-sm text-muted">Charts are unavailable right now; they will be back shortly.</Card>
  if (isPending || !data) return <Skeleton className="h-64" />
  if (data.expenses === 0) {
    return (
      <Card className="flex flex-col items-center gap-2 px-pad py-10 text-center text-sm text-muted">
        <BarChart3 className="size-6" />Charts appear once the group has expenses: who paid, what it went on, and when.
      </Card>
    )
  }
  const people = data.members.filter((m) => m.paid > 0 || m.share > 0 || !m.left)
  const nameOf = (m: { memberId: string; name: string }) => (m.memberId === group.you ? 'You' : m.name)
  const active = people.filter((m) => m.share > 0).length || 1
  const kpis = [
    { label: 'Spent together', value: money(data.spent), sub: `${data.expenses} expense${data.expenses === 1 ? '' : 's'}`, testId: 'group-spent' },
    { label: 'Your share', value: money(data.yourShare), sub: data.spent > 0 ? `${Math.round((data.yourShare / data.spent) * 100)}% of it` : '', testId: 'group-your-share' },
    { label: 'You paid', value: money(data.yourPaid), sub: data.yourPaid >= data.yourShare ? `${money(data.yourPaid - data.yourShare)} for others` : `${money(data.yourShare - data.yourPaid)} less than your share`, testId: 'group-you-paid' },
    { label: 'Per person', value: money(Math.round(data.spent / active)), sub: `${active} sharing · ${money(data.settled)} settled`, testId: 'group-per-person' },
  ]

  return (
    <div className="flex flex-col gap-4" aria-label="Group charts">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {kpis.map((k) => (
          <Card key={k.label} className="p-pad">
            <div className="text-xs text-muted">{k.label}</div>
            <div className="text-xl font-semibold tracking-tight tabular" data-testid={k.testId}>{k.value}</div>
            <div className="truncate text-xs text-subtle">{k.sub}</div>
          </Card>
        ))}
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <section aria-label="Who paid" className="flex flex-col gap-2">
          <h3 className="text-sm font-medium text-muted">Who paid</h3>
          <Card className="p-pad">
            <Donut label="Who paid" format={money} center={{ value: short(data.spent), caption: 'spent in all' }} onSelect={onMember}
              items={people.map((m, i) => ({ key: m.memberId, label: nameOf(m) + (m.left ? ' (left)' : ''), value: m.paid, slot: (i % 8) + 1 }))} />
          </Card>
        </section>
        <section aria-label="What it went on" className="flex flex-col gap-2">
          <h3 className="text-sm font-medium text-muted">What it went on</h3>
          <Card className="p-pad">
            <BarList format={money} onSelect={(key) => onLabel(key === '' ? null : key)}
              items={data.labels.slice(0, 8).map((l, i) => ({ key: l.label ?? '', label: `${l.label ?? 'No label'} · ${l.count}`, value: l.amount, slot: (i % 8) + 1 }))} />
          </Card>
        </section>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <section aria-label="Paid and share by person" className="flex flex-col gap-2">
        <h3 className="text-sm font-medium text-muted">Paid against share, by person</h3>
        <Card className="p-pad">
          <GroupedBars format={money} formatAxis={short} onSelect={(i) => onMember(people[i]!.memberId)}
            data={people.map((m) => ({ label: nameOf(m).slice(0, 10), values: [m.paid, m.share] }))}
            series={[{ name: 'Paid', slot: 1 }, { name: 'Share', slot: 2 }]} />
          <p className="mt-2 text-xs text-subtle">Paid more than their share: they're owed the difference. Less: they owe it.</p>
        </Card>
      </section>

        {data.months.length > 0 && (
          <section aria-label="Month by month" className="flex flex-col gap-2">
            <h3 className="text-sm font-medium text-muted">Month by month</h3>
            <Card className="p-pad">
              <GroupedBars format={money} formatAxis={short} onSelect={(i) => onMonth(data.months[i]!.month)}
                data={data.months.slice(-12).map((m) => {
                  const [y, mo] = m.month.split('-').map(Number)
                  return { label: new Date(y!, mo! - 1, 1).toLocaleDateString(locale, { month: 'short' }), values: [m.amount] }
                })}
                series={[{ name: 'Spent', slot: 3 }]} />
            </Card>
          </section>
        )}
      </div>

      <section aria-label="Biggest expenses" className="flex flex-col gap-2">
          <h3 className="text-sm font-medium text-muted">Biggest expenses</h3>
          <Card className="divide-y divide-border p-0">
            {data.biggest.map((b) => (
              <button key={b.id} type="button" onClick={() => onExpense(b.id)} aria-label={`Open ${b.description}`}
                className="flex w-full items-center gap-3 px-pad py-2.5 text-left text-sm hover:bg-surface-2 first:rounded-t-card last:rounded-b-card">
                <span className="min-w-0 flex-1 truncate font-medium">{b.description}</span>
                <span className="text-xs text-subtle">{new Date(`${b.occurredOn}T00:00`).toLocaleDateString(locale, { day: 'numeric', month: 'short' })}</span>
                <span className="tabular">{formatMoney(b.amount, group.currency, locale)}</span>
              </button>
            ))}
          </Card>
        </section>
    </div>
  )
}
