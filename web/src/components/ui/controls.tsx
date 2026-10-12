import { forwardRef, type InputHTMLAttributes, type ReactNode } from 'react'
import { Switch as SwitchPrimitive, RadioGroup, Tooltip as TooltipPrimitive } from 'radix-ui'
import { cn } from '@/lib/cn'

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(function Input({ className, ...props }, ref) {
  return (
    <input
      ref={ref}
      className={cn(
        'h-10 w-full rounded-control border border-border-strong bg-surface px-3 text-sm text-fg placeholder:text-subtle',
        'transition-colors focus:border-accent focus:outline-none focus-visible:ring-2 focus-visible:ring-ring',
        className,
      )}
      {...props}
    />
  )
})

export function Switch({ checked, onCheckedChange, label, id }: { checked: boolean; onCheckedChange: (v: boolean) => void; label: string; id?: string }) {
  return (
    <SwitchPrimitive.Root
      id={id}
      aria-label={label}
      checked={checked}
      onCheckedChange={onCheckedChange}
      className="relative inline-flex h-6 w-11 shrink-0 cursor-pointer items-center rounded-full bg-surface-3 transition-colors data-[state=checked]:bg-accent"
    >
      <SwitchPrimitive.Thumb className="block size-5 translate-x-0.5 rounded-full bg-white shadow transition-transform duration-200 data-[state=checked]:translate-x-[22px]" />
    </SwitchPrimitive.Root>
  )
}

/** A row of mutually exclusive options; arrow keys move between them. */
export function Segmented<T extends string>({ value, onChange, options, label, className }: {
  value: T
  onChange: (v: T) => void
  options: { value: T; label: ReactNode; title?: string }[]
  label: string
  className?: string
}) {
  return (
    <RadioGroup.Root
      aria-label={label}
      value={value}
      onValueChange={(v) => onChange(v as T)}
      className={cn('inline-flex rounded-control border border-border bg-surface-2 p-0.5', className)}
    >
      {options.map((o) => (
        <RadioGroup.Item
          key={o.value}
          value={o.value}
          title={o.title}
          className={cn(
            'inline-flex h-8 min-w-9 flex-1 items-center justify-center gap-1.5 whitespace-nowrap rounded-[calc(var(--radius-sm)-2px)] px-3 text-[0.8125rem] font-medium text-muted transition-all',
            'hover:text-fg data-[state=checked]:bg-surface data-[state=checked]:text-fg data-[state=checked]:shadow-sm [&_svg]:size-4',
          )}
        >
          {o.label}
        </RadioGroup.Item>
      ))}
    </RadioGroup.Root>
  )
}

export function Tooltip({ content, children, side = 'top' }: { content: ReactNode; children: ReactNode; side?: 'top' | 'bottom' | 'left' | 'right' }) {
  return (
    <TooltipPrimitive.Root delayDuration={350}>
      <TooltipPrimitive.Trigger asChild>{children}</TooltipPrimitive.Trigger>
      <TooltipPrimitive.Portal>
        <TooltipPrimitive.Content
          side={side}
          sideOffset={6}
          className="z-50 rounded-md bg-fg px-2 py-1 text-xs text-bg shadow-lift data-[state=delayed-open]:animate-in"
        >
          {content}
        </TooltipPrimitive.Content>
      </TooltipPrimitive.Portal>
    </TooltipPrimitive.Root>
  )
}
