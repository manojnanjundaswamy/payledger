import { forwardRef, type InputHTMLAttributes } from 'react'
import { cn } from '@/lib/cn'
import { currencySymbol, moneyDefaults } from '@/lib/money'
import { Input } from './controls'

/**
 * An amount box that always says which money it is: the currency's symbol in front ("₹", "$", "AED") and its code at
 * the end, so nobody types dirhams into a rupee account by mistake.
 */
export const MoneyInput = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement> & { currency?: string }>(
  function MoneyInput({ currency, className, ...props }, ref) {
    const code = currency ?? moneyDefaults().currency
    const symbol = currencySymbol(code)
    const showCode = symbol !== code
    return (
      <span className="relative flex w-full items-center" data-currency={code}>
        <span aria-hidden className="pointer-events-none absolute left-3 text-sm font-medium text-muted">{symbol}</span>
        <Input ref={ref} inputMode="decimal" autoComplete="off" {...props}
          className={cn('tabular', symbol.length > 1 ? 'pl-12' : 'pl-7', showCode && 'pr-12', className)} />
        {showCode && <span aria-hidden className="pointer-events-none absolute right-3 text-[0.6875rem] font-medium tracking-wide text-subtle">{code}</span>}
      </span>
    )
  })
