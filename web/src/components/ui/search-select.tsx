import { useEffect, useId, useMemo, useRef, useState, type ReactNode } from 'react'
import { Popover } from 'radix-ui'
import { Check, ChevronsUpDown, Plus, Search } from 'lucide-react'
import { cn } from '@/lib/cn'

export interface SearchOption {
  value: string
  /** What the option is called (and how it reads to a screen reader). */
  label: string
  /** A heading the option sits under, like "Spending". */
  group?: string
  icon?: ReactNode
  /** A subcategory, shown indented under its parent. */
  indent?: boolean
  /** Extra words to match when searching, such as the parent category's name. */
  keywords?: string
}

/**
 * A dropdown you can type in: the list narrows as you type, arrow keys move, Enter picks, Escape closes. It replaces
 * the browser's own select for long lists (categories, accounts), keeps groups and icons, and with `allowCustom` also
 * takes a new value that isn't in the list (a group's own label, say).
 */
export function SearchSelect({ id, value, onChange, options, placeholder = 'Choose…', allowCustom, customLabel = 'Use', className, disabled, ...aria }: {
  id?: string
  value: string
  onChange: (value: string) => void
  options: SearchOption[]
  placeholder?: string
  /** Offer "Use "<typed text>"" when nothing matches exactly; the typed text becomes the value. */
  allowCustom?: boolean
  customLabel?: string
  className?: string
  disabled?: boolean
  'aria-label'?: string
}) {
  const [open, setOpen] = useState(false)
  const [q, setQ] = useState('')
  const [active, setActive] = useState(0)
  const listId = useId()
  const listRef = useRef<HTMLUListElement>(null)
  const selected = options.find((o) => o.value === value)
  const needle = q.trim().toLowerCase()
  const shown = useMemo(() => !needle ? options
    : options.filter((o) => `${o.label} ${o.group ?? ''} ${o.keywords ?? ''}`.toLowerCase().includes(needle)), [options, needle])
  const custom = allowCustom && q.trim() && !options.some((o) => o.label.toLowerCase() === needle) ? q.trim() : null
  const count = shown.length + (custom ? 1 : 0)

  useEffect(() => {
    if (!open) return
    setQ('')
    setActive(Math.max(0, options.findIndex((o) => o.value === value)))
  }, [open]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => setActive(0), [needle])
  useEffect(() => {
    listRef.current?.querySelector(`[data-index="${active}"]`)?.scrollIntoView({ block: 'nearest' })
  }, [active])

  const pick = (v: string) => {
    onChange(v)
    setOpen(false)
  }

  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger asChild disabled={disabled}>
        <button type="button" id={id} role="combobox" aria-expanded={open} aria-controls={listId} aria-haspopup="listbox" {...aria}
          className={cn('flex h-10 w-full items-center gap-2 rounded-control border border-border-strong bg-surface px-3 text-left text-sm text-fg',
            'focus:border-accent focus:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-50', className)}>
          {selected?.icon && <span className="shrink-0 [&_svg]:size-4">{selected.icon}</span>}
          <span className={cn('min-w-0 flex-1 truncate', !selected && !(allowCustom && value) && 'text-subtle')}>
            {selected?.label ?? (allowCustom && value ? value : placeholder)}
          </span>
          <ChevronsUpDown className="size-4 shrink-0 text-subtle" />
        </button>
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content align="start" sideOffset={4} collisionPadding={8}
          className="z-[70] flex max-h-[min(22rem,var(--radix-popover-content-available-height))] w-[max(var(--radix-popover-trigger-width),14rem)] flex-col overflow-hidden rounded-control border border-border bg-surface shadow-lift data-[state=open]:animate-[pop-in_120ms_ease-out]"
          onOpenAutoFocus={(e) => { e.preventDefault(); (e.currentTarget as HTMLElement).querySelector('input')?.focus() }}>
          <label className="flex items-center gap-2 border-b border-border px-3">
            <Search className="size-4 shrink-0 text-subtle" />
            <input value={q} onChange={(e) => setQ(e.target.value)} placeholder={allowCustom ? 'Search or type a new one' : 'Search'}
              aria-label="Search the list" aria-controls={listId} aria-activedescendant={count ? `${listId}-${active}` : undefined}
              className="h-10 w-full bg-transparent text-sm outline-none placeholder:text-subtle"
              onKeyDown={(e) => {
                if (e.key === 'ArrowDown') { e.preventDefault(); setActive((a) => Math.min(a + 1, count - 1)) }
                if (e.key === 'ArrowUp') { e.preventDefault(); setActive((a) => Math.max(a - 1, 0)) }
                if (e.key === 'Enter') {
                  e.preventDefault()
                  if (active < shown.length) pick(shown[active]!.value)
                  else if (custom) pick(custom)
                }
              }} />
          </label>
          <ul id={listId} ref={listRef} role="listbox" className="overflow-y-auto p-1">
            {shown.map((o, i) => (
              <li key={o.value || `empty-${i}`}>
                {o.group && o.group !== shown[i - 1]?.group && (
                  <div className="px-2.5 pt-2 pb-1 text-[0.6875rem] font-medium tracking-wide text-subtle uppercase" aria-hidden>{o.group}</div>
                )}
                <div id={`${listId}-${i}`} data-index={i} role="option" aria-selected={o.value === value} aria-label={o.label}
                  onMouseMove={() => setActive(i)} onClick={() => pick(o.value)}
                  className={cn('flex cursor-pointer items-center gap-2 rounded-[calc(var(--radius-sm)-3px)] px-2.5 py-2 text-sm [&_svg]:size-4',
                    i === active && 'bg-surface-2', o.indent && 'pl-7')}>
                  {o.icon && <span className="shrink-0">{o.icon}</span>}
                  <span className="min-w-0 flex-1 truncate">{o.label}</span>
                  {o.value === value && <Check className="shrink-0 text-accent" />}
                </div>
              </li>
            ))}
            {custom && (
              <li>
                <div id={`${listId}-${shown.length}`} data-index={shown.length} role="option" aria-selected={false} aria-label={`${customLabel} "${custom}"`}
                  onMouseMove={() => setActive(shown.length)} onClick={() => pick(custom)}
                  className={cn('flex cursor-pointer items-center gap-2 rounded-[calc(var(--radius-sm)-3px)] px-2.5 py-2 text-sm [&_svg]:size-4', active === shown.length && 'bg-surface-2')}>
                  <Plus className="shrink-0" />{customLabel} “{custom}”
                </div>
              </li>
            )}
            {count === 0 && <li className="px-2.5 py-3 text-sm text-muted">Nothing matches “{q.trim()}”.</li>}
          </ul>
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  )
}
