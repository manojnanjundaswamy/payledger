import { useEffect, useState } from 'react'
import { Keyboard, Mic } from 'lucide-react'
import { openQuickAdd } from './QuickAdd'

const HINTS = ['“450 petrol yesterday on HDFC card”', '“Got 50k salary”', '“Paid Ravi 1,200 for dinner”', '“200 auto and 350 lunch”']

/** The dashboard's front door: tap the mic and say it, or tap the bar and type. */
export function QuickAddBar() {
  const [hint, setHint] = useState(0)
  useEffect(() => {
    const t = window.setInterval(() => setHint((i) => (i + 1) % HINTS.length), 3200)
    return () => window.clearInterval(t)
  }, [])
  return (
    <div className="flex items-center gap-2 rounded-full border border-border bg-surface p-1.5 pl-2 shadow-sm" data-tour="quick-add">
      <button type="button" onClick={() => openQuickAdd({ listen: true })} aria-label="Speak an entry"
        className="flex size-10 shrink-0 items-center justify-center rounded-full bg-accent text-accent-fg shadow-sm transition-transform active:scale-95">
        <Mic className="size-5" />
      </button>
      <button type="button" onClick={() => openQuickAdd()} className="flex min-w-0 flex-1 items-center gap-2 py-2 text-left">
        <span className="truncate text-sm text-muted">
          <span className="text-fg">Say or type what you spent</span> <span className="max-sm:hidden">· {HINTS[hint]}</span>
        </span>
        <Keyboard className="ml-auto size-4 shrink-0 text-subtle" aria-hidden />
      </button>
    </div>
  )
}
