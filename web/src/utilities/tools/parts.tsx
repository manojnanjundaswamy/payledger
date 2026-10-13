import type { ReactNode } from 'react'
import { Input } from '@/components/ui/controls'
import { cn } from '@/lib/cn'
import { evaluate } from '../expr'

/** A number from what was typed: "1.5L", "12k", "50,000", even "25000*12". Null if it isn't one. */
export function num(text: string): number | null {
  if (!text.trim()) return null
  try {
    const v = evaluate(text)
    return Number.isFinite(v) ? v : null
  } catch {
    return null
  }
}

/** ₹1,23,456 (or with paise when asked). */
export function rupees(v: number, decimals = 0): string {
  return v.toLocaleString('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: decimals, minimumFractionDigits: decimals })
}

export function NumberField({ id, label, value, onChange, suffix, hint, placeholder }: {
  id: string; label: string; value: string; onChange: (v: string) => void; suffix?: string; hint?: string; placeholder?: string
}) {
  const bad = value.trim() !== '' && num(value) === null
  return (
    <label htmlFor={id} className="flex flex-col gap-1 text-sm">
      <span className="font-medium">{label}</span>
      <span className="relative">
        <Input id={id} aria-label={label} inputMode="decimal" autoComplete="off" value={value} placeholder={placeholder} onChange={(e) => onChange(e.target.value)}
          className={cn('tabular', suffix && 'pr-14', bad && 'border-critical')} aria-invalid={bad} />
        {suffix && <span className="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 text-xs text-subtle">{suffix}</span>}
      </span>
      {hint && <span className="text-xs text-subtle">{hint}</span>}
    </label>
  )
}

/** The answer, big, with the lines that explain it. */
export function Result({ label, value, testId, children }: { label: string; value: string; testId?: string; children?: ReactNode }) {
  return (
    <div className="flex flex-col gap-2 rounded-card bg-surface-2 p-4">
      <span className="text-xs text-muted">{label}</span>
      <span className="text-2xl font-semibold tracking-tight tabular" data-testid={testId}>{value}</span>
      {children && <dl className="flex flex-col gap-1 text-sm">{children}</dl>}
    </div>
  )
}

export function Line({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <div className="flex justify-between gap-4">
      <dt className="text-muted">{label}</dt>
      <dd className={cn('tabular', strong && 'font-semibold')}>{value}</dd>
    </div>
  )
}

/** Two parts of a whole as one bar, labelled (principal vs interest, invested vs gains). */
export function SplitBar({ a, b, aLabel, bLabel }: { a: number; b: number; aLabel: string; bLabel: string }) {
  const total = a + b || 1
  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex h-2.5 overflow-hidden rounded-full bg-surface-3" role="img" aria-label={`${aLabel} ${Math.round((a / total) * 100)}%, ${bLabel} ${Math.round((b / total) * 100)}%`}>
        <span style={{ width: `${(a / total) * 100}%`, background: 'var(--series-1)' }} />
        <span style={{ width: `${(b / total) * 100}%`, background: 'var(--series-2)' }} />
      </div>
      <div className="flex justify-between text-xs text-muted">
        <span className="flex items-center gap-1.5"><span className="size-2 rounded-[2px]" style={{ background: 'var(--series-1)' }} />{aLabel} {Math.round((a / total) * 100)}%</span>
        <span className="flex items-center gap-1.5"><span className="size-2 rounded-[2px]" style={{ background: 'var(--series-2)' }} />{bLabel} {Math.round((b / total) * 100)}%</span>
      </div>
    </div>
  )
}

export function Note({ children }: { children: ReactNode }) {
  return <p className="text-xs text-subtle">{children}</p>
}
