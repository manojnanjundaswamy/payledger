import { forwardRef, type ButtonHTMLAttributes } from 'react'
import { Slot } from 'radix-ui'
import { cn } from '@/lib/cn'

const variants = {
  primary: 'bg-accent text-accent-fg hover:opacity-90 shadow-sm',
  secondary: 'bg-surface-2 text-fg hover:bg-surface-3 border border-border',
  outline: 'border border-border-strong bg-transparent text-fg hover:bg-surface-2',
  ghost: 'text-muted hover:text-fg hover:bg-surface-2',
  danger: 'bg-critical text-white hover:opacity-90',
} as const

const sizes = {
  sm: 'h-8 px-3 text-[0.8125rem] gap-1.5',
  md: 'h-10 px-4 text-sm gap-2',
  lg: 'h-12 px-6 text-base gap-2',
  icon: 'h-9 w-9 p-0',
  'icon-sm': 'h-7 w-7 p-0',
} as const

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: keyof typeof variants
  size?: keyof typeof sizes
  asChild?: boolean
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { className, variant = 'secondary', size = 'md', asChild, type = 'button', ...props }, ref,
) {
  const Comp = asChild ? Slot.Root : 'button'
  return (
    <Comp
      ref={ref}
      type={asChild ? undefined : type}
      className={cn(
        'inline-flex shrink-0 select-none items-center justify-center rounded-control font-medium whitespace-nowrap',
        'transition-[background-color,color,opacity,transform,box-shadow] duration-150 active:scale-[0.97]',
        'disabled:pointer-events-none disabled:opacity-50 [&_svg]:size-4 [&_svg]:shrink-0',
        variants[variant], sizes[size], className,
      )}
      {...props}
    />
  )
})
