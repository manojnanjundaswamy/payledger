import type { ReactNode } from 'react'
import { Dialog as DialogPrimitive, DropdownMenu } from 'radix-ui'
import { X } from 'lucide-react'
import { cn } from '@/lib/cn'

/** Centered dialog on desktop, bottom sheet on phones. */
export function Dialog({ open, onOpenChange, title, description, children, side = 'center', className }: {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description?: string
  children: ReactNode
  side?: 'center' | 'right'
  className?: string
}) {
  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-40 bg-[var(--overlay)] backdrop-blur-[2px] data-[state=open]:animate-[fade-in_150ms_ease-out]" />
        <DialogPrimitive.Content
          className={cn(
            'fixed z-50 flex flex-col overflow-hidden border border-border bg-surface shadow-lift focus:outline-none',
            side === 'center'
              ? 'inset-x-0 bottom-0 max-h-[88dvh] rounded-t-[calc(var(--radius)+6px)] sm:inset-auto sm:top-1/2 sm:left-1/2 sm:w-[min(92vw,560px)] sm:-translate-x-1/2 sm:-translate-y-1/2 sm:rounded-card data-[state=open]:animate-[sheet-up_220ms_cubic-bezier(.2,.8,.2,1)] sm:data-[state=open]:animate-[pop-in_180ms_cubic-bezier(.2,.8,.2,1)]'
              : 'inset-y-0 right-0 w-[min(100vw,440px)] data-[state=open]:animate-[slide-in_220ms_cubic-bezier(.2,.8,.2,1)]',
            className,
          )}
        >
          <div className="flex items-start justify-between gap-4 border-b border-border px-pad py-4">
            <div>
              <DialogPrimitive.Title className="text-base font-semibold">{title}</DialogPrimitive.Title>
              {description ? <DialogPrimitive.Description className="mt-0.5 text-sm text-muted">{description}</DialogPrimitive.Description> : <DialogPrimitive.Description className="sr-only">{title}</DialogPrimitive.Description>}
            </div>
            <DialogPrimitive.Close className="-m-1 rounded-control p-1.5 text-muted hover:bg-surface-2 hover:text-fg" aria-label="Close">
              <X className="size-4" />
            </DialogPrimitive.Close>
          </div>
          <div className="overflow-y-auto">{children}</div>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}

export const Menu = DropdownMenu.Root
export const MenuTrigger = DropdownMenu.Trigger

export function MenuContent({ children, align = 'end' }: { children: ReactNode; align?: 'start' | 'end' | 'center' }) {
  return (
    <DropdownMenu.Portal>
      <DropdownMenu.Content
        align={align}
        sideOffset={6}
        className="z-50 min-w-48 rounded-control border border-border bg-surface p-1 shadow-lift data-[state=open]:animate-[pop-in_120ms_ease-out]"
      >
        {children}
      </DropdownMenu.Content>
    </DropdownMenu.Portal>
  )
}

export function MenuItem({ children, onSelect, destructive, icon }: { children: ReactNode; onSelect?: () => void; destructive?: boolean; icon?: ReactNode }) {
  return (
    <DropdownMenu.Item
      onSelect={onSelect}
      className={cn(
        'flex cursor-pointer select-none items-center gap-2 rounded-[calc(var(--radius-sm)-3px)] px-2.5 py-2 text-sm outline-none [&_svg]:size-4',
        destructive ? 'text-critical data-[highlighted]:bg-critical/10' : 'data-[highlighted]:bg-surface-2',
      )}
    >
      {icon}
      {children}
    </DropdownMenu.Item>
  )
}

export function MenuLabel({ children }: { children: ReactNode }) {
  return <DropdownMenu.Label className="px-2.5 pt-2 pb-1 text-[0.6875rem] font-medium tracking-wide text-subtle uppercase">{children}</DropdownMenu.Label>
}

export function MenuSeparator() {
  return <DropdownMenu.Separator className="my-1 h-px bg-border" />
}

export const MenuRadioGroup = DropdownMenu.RadioGroup

export function MenuRadioItem({ value, children }: { value: string; children: ReactNode }) {
  return (
    <DropdownMenu.RadioItem
      value={value}
      className="relative flex cursor-pointer select-none items-center rounded-[calc(var(--radius-sm)-3px)] py-2 pr-2.5 pl-7 text-sm outline-none data-[highlighted]:bg-surface-2"
    >
      <DropdownMenu.ItemIndicator className="absolute left-2.5 size-1.5 rounded-full bg-accent" />
      {children}
    </DropdownMenu.RadioItem>
  )
}
