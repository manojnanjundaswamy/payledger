import { useLayoutEffect, useRef, useState } from 'react'
import { Legend, seriesColor } from '@/dashboard/charts'

/** Active users (line) and sign-ups (bars) per day; hover a day for both numbers. */
export function DailyChart({ days, locale }: { days: { day: string; active: number; signups: number }[]; locale: string }) {
  const [active, setActive] = useState<number | null>(null)
  // Drawn at the real width, so text stays 11px on a phone and on a wide screen.
  const box = useRef<HTMLDivElement>(null)
  const [W, setW] = useState(600)
  useLayoutEffect(() => {
    const el = box.current
    if (!el) return
    const observer = new ResizeObserver(([entry]) => setW(Math.max(280, Math.round(entry!.contentRect.width))))
    observer.observe(el)
    return () => observer.disconnect()
  }, [])
  const H = 200
  const pad = { top: 8, right: 4, bottom: 20, left: 32 }
  const plotW = W - pad.left - pad.right
  const plotH = H - pad.top - pad.bottom
  const max = Math.max(...days.flatMap((d) => [d.active, d.signups]), 1)
  const top = max <= 5 ? 5 : Math.ceil(max / 5) * 5
  const col = plotW / days.length
  const x = (i: number) => pad.left + col * i + col / 2
  const y = (v: number) => pad.top + plotH - (v / top) * plotH
  const line = days.map((d, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(d.active).toFixed(1)}`).join(' ')
  const label = (iso: string) => new Date(`${iso}T00:00:00`).toLocaleDateString(locale, { day: 'numeric', month: 'short' })
  // A date label about every 90px, whatever the range.
  const every = Math.max(1, Math.ceil(days.length / Math.max(2, Math.floor(plotW / 90))))
  const shown = active ?? days.length - 1
  const series = [{ name: 'Active users', slot: 1 }, { name: 'Sign-ups', slot: 2 }]

  return (
    <figure className="flex flex-col gap-2">
      <figcaption className="flex flex-wrap items-center justify-between gap-2 text-xs text-muted">
        <Legend series={series} />
        <span data-testid="daily-shown">{label(days[shown]!.day)}: <b className="text-fg tabular">{days[shown]!.active}</b> active, <b className="text-fg tabular">{days[shown]!.signups}</b> sign-ups</span>
      </figcaption>
      <div ref={box} className="overflow-hidden">
      <svg width={W} height={H} viewBox={`0 0 ${W} ${H}`} className="block overflow-visible" role="img" onMouseLeave={() => setActive(null)}
        aria-label={`Active users and sign-ups per day, ${label(days[0]!.day)} to ${label(days[days.length - 1]!.day)}`}>
        {[0, top / 2, top].map((t) => (
          <g key={t}>
            <line x1={pad.left} x2={W - pad.right} y1={y(t)} y2={y(t)} stroke="var(--border)" />
            <text x={pad.left - 6} y={y(t)} dy="0.32em" textAnchor="end" className="fill-subtle text-[11px] tabular">{t}</text>
          </g>
        ))}
        {days.map((d, i) => (
          <g key={d.day} onMouseEnter={() => setActive(i)}>
            <rect x={x(i) - col / 2} y={pad.top} width={col} height={plotH} fill={active === i ? 'var(--surface-2)' : 'transparent'} />
            {d.signups > 0 && <rect x={x(i) - Math.min(8, col * 0.6) / 2} y={y(d.signups)} width={Math.min(8, col * 0.6)} height={pad.top + plotH - y(d.signups)}
              rx={2} fill={seriesColor(2)} />}
            {i % every === (days.length - 1) % every && (
              <text x={x(i)} y={H - 4} textAnchor={i === days.length - 1 ? "end" : "middle"} className="fill-subtle text-[11px]">{label(d.day)}</text>
            )}
          </g>
        ))}
        <path d={line} fill="none" stroke={seriesColor(1)} strokeWidth={2} strokeLinejoin="round" pointerEvents="none" />
        <circle cx={x(shown)} cy={y(days[shown]!.active)} r={4} fill={seriesColor(1)} pointerEvents="none" />
      </svg>
      </div>
    </figure>
  )
}
