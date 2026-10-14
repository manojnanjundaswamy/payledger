import { useId, useState, type ReactNode } from 'react'
import { motion } from 'motion/react'
import { AlertTriangle, CheckCircle2, CircleAlert } from 'lucide-react'
import { cn } from '@/lib/cn'

export const seriesColor = (slot: number) => (slot > 0 ? `var(--series-${slot})` : 'var(--fg-subtle)')

/**
 * Part-to-whole as a ranked list of horizontal bars: every bar is labelled with its name and value,
 * so colour is never the only cue (no legend needed). Hover shows the share of the total.
 */
export function BarList({ items, format, onSelect }: {
  items: { key: string; label: string; value: number; slot: number }[]
  format: (v: number) => string
  /** Makes each row clickable, e.g. to open that category's transactions. */
  onSelect?: (key: string) => void
}) {
  const total = items.reduce((s, i) => s + i.value, 0)
  const max = Math.max(...items.map((i) => i.value), 1)
  const [hover, setHover] = useState<string | null>(null)
  return (
    <ul className="flex flex-col gap-2.5" role="list">
      {items.map((item, i) => {
        const label = `${item.label}: ${format(item.value)}, ${Math.round((item.value / total) * 100)}%`
        const body = (
          <>
            <div className="mb-1 flex items-baseline justify-between gap-3 text-[0.8125rem]">
              <span className="flex min-w-0 items-center gap-2">
                <span className="size-2.5 shrink-0 rounded-[3px]" style={{ background: seriesColor(item.slot) }} />
                <span className={cn('truncate', onSelect && 'group-hover:underline group-hover:underline-offset-4')}>{item.label}</span>
              </span>
              <span className="tabular shrink-0 text-muted">
                {hover === item.key ? `${((item.value / total) * 100).toFixed(1)}%` : format(item.value)}
              </span>
            </div>
            <div className="h-2 rounded-full bg-surface-2">
              <motion.div
                className="h-full rounded-full"
                style={{ background: seriesColor(item.slot) }}
                initial={{ width: 0 }}
                animate={{ width: `${(item.value / max) * 100}%` }}
                transition={{ duration: 0.6, delay: i * 0.04, ease: [0.2, 0.8, 0.2, 1] }}
              />
            </div>
          </>
        )
        return (
          <li key={item.key} onMouseEnter={() => setHover(item.key)} onMouseLeave={() => setHover(null)} className="group relative"
            aria-label={onSelect ? undefined : label}>
            {onSelect
              ? <button type="button" onClick={() => onSelect(item.key)} aria-label={label} className="-m-1 block w-[calc(100%+0.5rem)] rounded-control p-1 text-left hover:bg-surface-2">{body}</button>
              : body}
          </li>
        )
      })}
    </ul>
  )
}

/**
 * Two series per period on ONE axis (income vs spending). Legend + tooltip per column; the whole
 * column is the hover target, not just the thin bar.
 */
export function GroupedBars({ data, series, format, formatAxis, onSelect }: {
  data: { label: string; values: number[] }[]
  series: { name: string; slot: number }[]
  format: (v: number) => string
  formatAxis: (v: number) => string
  /** Makes each column clickable (by index), e.g. to open that month's report. */
  onSelect?: (index: number) => void
}) {
  const [active, setActive] = useState<number | null>(null)
  const clip = useId()
  const W = 600
  const H = 200
  const pad = { top: 8, right: 4, bottom: 22, left: 44 }
  const plotW = W - pad.left - pad.right
  const plotH = H - pad.top - pad.bottom
  const maxValue = Math.max(...data.flatMap((d) => d.values), 1)
  const step = niceStep(maxValue / 3)
  const top = Math.ceil(maxValue / step) * step
  const ticks = Array.from({ length: Math.round(top / step) + 1 }, (_, i) => i * step)
  const col = plotW / data.length
  const gap = 2
  const barW = Math.min(18, (col * 0.6 - gap) / series.length)
  const y = (v: number) => pad.top + plotH - (v / top) * plotH

  return (
    <div className="relative">
      <Legend series={series} />
      <svg viewBox={`0 0 ${W} ${H}`} className="mt-2 w-full overflow-visible" role="img" aria-label={`${series.map((s) => s.name).join(' and ')} by month`}>
        <defs><clipPath id={clip}><rect x={pad.left} y={pad.top} width={plotW} height={plotH} /></clipPath></defs>
        {ticks.map((t) => (
          <g key={t}>
            <line x1={pad.left} x2={W - pad.right} y1={y(t)} y2={y(t)} stroke="var(--border)" strokeWidth={t === 0 ? 1.5 : 1} />
            <text x={pad.left - 8} y={y(t)} dy="0.32em" textAnchor="end" className="fill-subtle text-[11px] tabular">{formatAxis(t)}</text>
          </g>
        ))}
        {data.map((d, i) => {
          const cx = pad.left + col * i + col / 2
          const groupW = series.length * barW + (series.length - 1) * gap
          return (
            <g key={d.label} onMouseEnter={() => setActive(i)} onMouseLeave={() => setActive(null)}
              {...(onSelect ? {
                role: 'button', tabIndex: 0, 'aria-label': `${d.label}: ${series.map((s, k) => `${s.name} ${format(d.values[k] ?? 0)}`).join(', ')}`,
                className: 'cursor-pointer focus:outline-none', onClick: () => onSelect(i), onFocus: () => setActive(i), onBlur: () => setActive(null),
                onKeyDown: (e: React.KeyboardEvent) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onSelect(i) } },
              } : {})}>
              <rect x={pad.left + col * i} y={pad.top} width={col} height={plotH} className={cn('fill-transparent', active === i && 'fill-[var(--surface-2)]')} />
              {d.values.map((v, s) => {
                const h = Math.max(0, (v / top) * plotH)
                const x = cx - groupW / 2 + s * (barW + gap)
                return (
                  <motion.path
                    key={s}
                    d={roundedTop(x, y(v), barW, h, Math.min(4, barW / 2))}
                    fill={seriesColor(series[s]!.slot)}
                    initial={{ opacity: 0, scaleY: 0 }}
                    animate={{ opacity: 1, scaleY: 1 }}
                    style={{ transformOrigin: `${x}px ${pad.top + plotH}px` }}
                    transition={{ duration: 0.5, delay: i * 0.04 }}
                    clipPath={`url(#${clip})`}
                  />
                )
              })}
              <text x={cx} y={H - 4} textAnchor="middle" className={cn('text-[11px]', active === i ? 'fill-fg' : 'fill-subtle')}>{d.label}</text>
            </g>
          )
        })}
      </svg>
      {active !== null && (
        <ChartTooltip x={(pad.left + col * active + col / 2) / W}>
          <div className="mb-1 font-medium">{data[active]!.label}</div>
          {series.map((s, i) => (
            <div key={s.name} className="flex items-center justify-between gap-4">
              <span className="flex items-center gap-1.5 text-muted"><span className="size-2 rounded-[2px]" style={{ background: seriesColor(s.slot) }} />{s.name}</span>
              <span className="tabular">{format(data[active]!.values[i]!)}</span>
            </div>
          ))}
        </ChartTooltip>
      )}
    </div>
  )
}

export function Legend({ series }: { series: { name: string; slot: number }[] }) {
  return (
    <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted">
      {series.map((s) => (
        <span key={s.name} className="flex items-center gap-1.5">
          <span className="size-2.5 rounded-[3px]" style={{ background: seriesColor(s.slot) }} />
          {s.name}
        </span>
      ))}
    </div>
  )
}

function ChartTooltip({ x, children }: { x: number; children: ReactNode }) {
  return (
    <div
      className="pointer-events-none absolute top-6 z-10 min-w-36 rounded-control border border-border bg-surface px-3 py-2 text-xs shadow-lift"
      style={{ left: `${x * 100}%`, transform: `translateX(${x > 0.6 ? '-105%' : '5%'})` }}
    >
      {children}
    </div>
  )
}

/** Status is always icon + words, never colour alone. */
/** How each budget status looks: colour plus icon plus text, never colour alone. */
export const BUDGET_STATUS = {
  OVER: { tone: 'critical' as const, label: 'Over', icon: CircleAlert, color: 'var(--critical)' },
  CLOSE: { tone: 'warning' as const, label: 'Close', icon: AlertTriangle, color: 'var(--warning)' },
  ON_TRACK: { tone: 'good' as const, label: 'On track', icon: CheckCircle2, color: 'var(--good)' },
}

export function budgetStatus(spent: number, limit: number) {
  const ratio = limit > 0 ? spent / limit : 0
  // Close from 80%, as core-service decides it (and where the plan puts the first alert).
  const status = ratio > 1 ? 'OVER' : ratio >= 0.8 ? 'CLOSE' : 'ON_TRACK'
  return { ...BUDGET_STATUS[status], ratio }
}

export function Progress({ value, color = 'var(--accent)', label }: { value: number; color?: string; label: string }) {
  return (
    <div className="h-2 overflow-hidden rounded-full bg-surface-2" role="progressbar" aria-label={label} aria-valuenow={Math.round(value * 100)} aria-valuemin={0} aria-valuemax={100}>
      <motion.div className="h-full rounded-full" style={{ background: color }}
        initial={{ width: 0 }} animate={{ width: `${Math.min(1, value) * 100}%` }} transition={{ duration: 0.7, ease: [0.2, 0.8, 0.2, 1] }} />
    </div>
  )
}

function niceStep(raw: number): number {
  const exp = 10 ** Math.floor(Math.log10(Math.max(raw, 1)))
  const f = raw / exp
  return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10) * exp
}

function roundedTop(x: number, y: number, w: number, h: number, r: number): string {
  if (h <= 0) return ''
  const rr = Math.min(r, h)
  return `M${x},${y + h} V${y + rr} Q${x},${y} ${x + rr},${y} H${x + w - rr} Q${x + w},${y} ${x + w},${y + rr} V${y + h} Z`
}

/**
 * Parts of a whole as a ring, with the total in the middle; each slice and legend row can be clicked. Used for who paid
 * what in a group. Colours come from the validated series palette.
 */
export function Donut({ items, format, center, label, onSelect }: {
  items: { key: string; label: string; value: number; slot: number }[]
  format: (v: number) => string
  center: { value: string; caption: string }
  label: string
  onSelect?: (key: string) => void
}) {
  const [active, setActive] = useState<string | null>(null)
  const total = items.reduce((s, i) => s + i.value, 0)
  const R = 42
  const C = 2 * Math.PI * R
  let offset = 0
  const shown = items.filter((i) => i.value > 0)
  const focus = shown.find((i) => i.key === active)
  return (
    <figure className="flex flex-wrap items-center gap-5" aria-label={label}>
      <svg viewBox="0 0 100 100" className="size-36 shrink-0 -rotate-90" role="img" aria-label={`${label}: ${shown.map((i) => `${i.label} ${format(i.value)}`).join(', ')}`}>
        <circle cx="50" cy="50" r={R} fill="none" stroke="var(--surface-2)" strokeWidth="12" />
        {shown.map((i) => {
          const len = total > 0 ? (i.value / total) * C : 0
          const el = (
            <circle key={i.key} cx="50" cy="50" r={R} fill="none" stroke={seriesColor(i.slot)} strokeWidth={active === i.key ? 15 : 12}
              strokeDasharray={`${Math.max(len - (shown.length > 1 ? 0.8 : 0), 0)} ${C}`} strokeDashoffset={-offset}
              className={cn('transition-[stroke-width]', onSelect && 'cursor-pointer')}
              onMouseEnter={() => setActive(i.key)} onMouseLeave={() => setActive(null)} onClick={() => onSelect?.(i.key)} />
          )
          offset += len
          return el
        })}
        <g className="rotate-90 origin-center">
          <text x="50" y="49" textAnchor="middle" className="fill-[var(--fg)] text-[11px] font-semibold tabular">{focus ? format(focus.value) : center.value}</text>
          <text x="50" y="61" textAnchor="middle" className="fill-[var(--fg-muted)] text-[7px]">{focus ? `${Math.round((focus.value / total) * 100)}% · ${focus.label}`.slice(0, 26) : center.caption}</text>
        </g>
      </svg>
      <ul className="flex min-w-40 flex-1 flex-col gap-1.5 text-[0.8125rem]">
        {items.map((i) => {
          const row = (
            <>
              <span className="size-2.5 shrink-0 rounded-[3px]" style={{ background: seriesColor(i.slot) }} />
              <span className="min-w-0 flex-1 truncate">{i.label}</span>
              <span className="tabular text-muted">{format(i.value)}</span>
              <span className="w-9 text-right text-xs text-subtle tabular">{total > 0 ? Math.round((i.value / total) * 100) : 0}%</span>
            </>
          )
          return (
            <li key={i.key} onMouseEnter={() => setActive(i.key)} onMouseLeave={() => setActive(null)}>
              {onSelect
                ? <button type="button" className="-mx-1 flex w-[calc(100%+0.5rem)] items-center gap-2 rounded-control px-1 py-0.5 text-left hover:bg-surface-2" onClick={() => onSelect(i.key)}>{row}</button>
                : <span className="flex items-center gap-2">{row}</span>}
            </li>
          )
        })}
      </ul>
    </figure>
  )
}
