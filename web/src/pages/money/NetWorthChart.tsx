import { useId, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { formatMoneyShort, formatMoneyWhole } from '@/lib/money'
import { useSpaceId } from '@/state/money'
import { useProfile } from '@/state/profile'

interface Point { month: string; netWorth: number }

/** Net worth at the end of each of the last 12 months (this month: today), from the ledger. */
export function NetWorthChart() {
  const spaceId = useSpaceId()
  const locale = useProfile().data?.settings.locale ?? 'en-IN'
  const { data, isPending, isError } = useQuery({
    queryKey: ['money', spaceId, 'net-worth'],
    queryFn: () => api<Point[]>(`/api/core/spaces/${spaceId}/net-worth?months=12`),
    enabled: !!spaceId,
  })
  const [active, setActive] = useState<number | null>(null)
  const gradient = useId()

  if (isError) return <p className="text-xs text-subtle">The net-worth trend is unavailable right now.</p>
  if (isPending || !data) return <div className="h-32 animate-pulse rounded-control bg-surface-2" />

  const W = 600
  const H = 120
  const values = data.map((p) => p.netWorth)
  const min = Math.min(...values, 0)
  const max = Math.max(...values, 1)
  const x = (i: number) => (i / Math.max(1, data.length - 1)) * W
  const y = (v: number) => H - 8 - ((v - min) / (max - min || 1)) * (H - 16)
  const line = data.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(p.netWorth).toFixed(1)}`).join(' ')
  const label = (month: string) => {
    const [yy, mm] = month.split('-').map(Number)
    return new Date(yy!, mm! - 1, 1).toLocaleDateString(locale, { month: 'short', year: '2-digit' })
  }
  const shown = active ?? data.length - 1
  const change = data[data.length - 1]!.netWorth - data[0]!.netWorth

  return (
    <figure className="flex flex-col gap-2" aria-label="Net worth over the last 12 months">
      <figcaption className="flex flex-wrap items-baseline justify-between gap-2 text-xs text-muted">
        <span>{label(data[shown]!.month)}: <span className="font-medium text-fg tabular">{formatMoneyWhole(data[shown]!.netWorth, 'INR', locale)}</span></span>
        <span className={change >= 0 ? 'text-good-text' : 'text-critical'}>
          {change >= 0 ? '+' : '−'}{formatMoneyShort(Math.abs(change), 'INR', locale)} in 12 months
        </span>
      </figcaption>
      <svg viewBox={`0 0 ${W} ${H}`} className="h-32 w-full overflow-visible" preserveAspectRatio="none" role="img"
        aria-label={data.map((p) => `${label(p.month)} ${formatMoneyWhole(p.netWorth, 'INR', locale)}`).join(', ')}
        onMouseLeave={() => setActive(null)}>
        <defs>
          <linearGradient id={gradient} x1="0" x2="0" y1="0" y2="1">
            <stop offset="0%" stopColor="var(--series-1)" stopOpacity="0.25" />
            <stop offset="100%" stopColor="var(--series-1)" stopOpacity="0" />
          </linearGradient>
        </defs>
        {min < 0 && <line x1={0} x2={W} y1={y(0)} y2={y(0)} stroke="var(--border-strong)" strokeDasharray="4 4" />}
        <path d={`${line} L${W},${H} L0,${H} Z`} fill={`url(#${gradient})`} />
        <path d={line} fill="none" stroke="var(--series-1)" strokeWidth="2" vectorEffect="non-scaling-stroke" />
        {data.map((p, i) => (
          <rect key={p.month} x={x(i) - W / data.length / 2} y={0} width={W / data.length} height={H} fill="transparent"
            onMouseEnter={() => setActive(i)} />
        ))}
        <circle cx={x(shown)} cy={y(data[shown]!.netWorth)} r={4} fill="var(--series-1)" vectorEffect="non-scaling-stroke" />
      </svg>
      <div className="flex justify-between text-[0.6875rem] text-subtle">
        <span>{label(data[0]!.month)}</span><span>{label(data[data.length - 1]!.month)}</span>
      </div>
    </figure>
  )
}
