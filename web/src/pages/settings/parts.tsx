import type { ReactNode } from 'react'

export function Section({ title, description, children }: { title: string; description?: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-5">
      <div>
        <h3 className="text-base font-semibold">{title}</h3>
        {description && <p className="mt-1 max-w-prose text-sm text-muted">{description}</p>}
      </div>
      <div className="flex flex-col divide-y divide-border rounded-card border border-border bg-surface">{children}</div>
    </section>
  )
}

export function Field({ label, hint, children, htmlFor }: { label: string; hint?: string; children: ReactNode; htmlFor?: string }) {
  return (
    <div className="flex flex-col gap-3 p-pad sm:flex-row sm:items-start sm:justify-between sm:gap-8">
      <div className="sm:max-w-xs sm:flex-1">
        <label htmlFor={htmlFor} className="text-sm font-medium">{label}</label>
        {hint && <p className="mt-0.5 text-xs text-muted">{hint}</p>}
      </div>
      <div className="min-w-0 sm:flex sm:shrink-0 sm:justify-end">{children}</div>
    </div>
  )
}

export function Select({ value, onChange, options, id, label }: { value: string; onChange: (v: string) => void; options: { value: string; label: string }[]; id?: string; label: string }) {
  return (
    <select id={id} aria-label={label} value={value} onChange={(e) => onChange(e.target.value)}
      className="h-10 w-full rounded-control border border-border-strong bg-surface px-3 text-sm text-fg focus:border-accent focus:outline-none sm:w-64">
      {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
    </select>
  )
}
