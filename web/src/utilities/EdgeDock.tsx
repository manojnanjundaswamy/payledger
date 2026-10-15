import { useEffect, useRef, useState } from 'react'
import { Link } from '@tanstack/react-router'
import { Calculator, ChevronLeft, LayoutGrid, X } from 'lucide-react'
import { cn } from '@/lib/cn'
import { TOOL_IDS, TOOLS } from './registry'
import { useUtilityWindows } from './windows'

const POSITION = 'edge-dock-y'
export const DOCK_HIDDEN = 'edge-dock-hidden'

function read(key: string): string | null {
  try { return localStorage.getItem(key) } catch { return null }
}

/**
 * A handle on the right edge (like a tablet's edge panel): drag it up or down, tap it for the utilities; each opens in its
 * own floating window (a sheet on phones). Where it sits, and whether it is shown at all, is remembered in this browser.
 * It shows on phones too: the installed app is where people reach for a quick EMI or split most.
 */
export function EdgeDock() {
  const [open, setOpen] = useState(false)
  const [hidden, setHidden] = useState(() => read(DOCK_HIDDEN) === '1')
  const [y, setY] = useState(() => Number(read(POSITION) ?? 45))
  const windows = useUtilityWindows()
  const drag = useRef<{ startY: number; startPct: number; moved: boolean } | null>(null)

  useEffect(() => {
    const onToggle = () => setHidden(read(DOCK_HIDDEN) === '1')
    window.addEventListener('edge-dock-change', onToggle)
    return () => window.removeEventListener('edge-dock-change', onToggle)
  }, [])

  useEffect(() => {
    const move = (e: PointerEvent) => {
      const d = drag.current
      if (!d) return
      const pct = Math.max(12, Math.min(85, d.startPct + ((e.clientY - d.startY) / window.innerHeight) * 100))
      if (Math.abs(e.clientY - d.startY) > 4) d.moved = true
      setY(pct)
    }
    const up = () => {
      const d = drag.current
      drag.current = null
      if (!d) return
      if (d.moved) { try { localStorage.setItem(POSITION, String(Math.round(y))) } catch { /* not remembered */ } } else setOpen((o) => !o)
    }
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', up)
    return () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up) }
  }, [y])

  if (hidden) return null
  return (
    <div className="fixed right-0 z-[55] flex items-center print:hidden" data-tour="tools" style={{ top: `${y}%`, transform: 'translateY(-50%)' }}>
      {open && (
        <div role="dialog" aria-label="Utilities" className="mr-1 w-[min(15rem,calc(100vw-4rem))] rounded-card border border-border bg-surface p-2 shadow-lift data-[state=open]:animate-[pop-in_120ms_ease-out]">
          <div className="flex items-center justify-between px-1 pb-1">
            <span className="text-xs font-medium tracking-wide text-subtle uppercase">Utilities</span>
            <button type="button" onClick={() => setOpen(false)} aria-label="Close utilities" className="rounded-control p-1 text-muted hover:text-fg"><X className="size-3.5" /></button>
          </div>
          <div className="grid grid-cols-3 gap-1">
            {TOOL_IDS.map((id) => {
              const t = TOOLS[id]
              return (
                <button key={id} type="button" onClick={() => { windows.open(id); setOpen(false) }}
                  className="flex flex-col items-center gap-1 rounded-control p-2 text-center text-[0.6875rem] leading-tight hover:bg-surface-2">
                  <span className="flex size-9 items-center justify-center rounded-full bg-surface-2"><t.icon className="size-4" /></span>
                  {t.name}
                </button>
              )
            })}
          </div>
          <Link to="/utilities" onClick={() => setOpen(false)} className="mt-1 flex items-center justify-center gap-1.5 rounded-control py-1.5 text-xs text-muted hover:bg-surface-2 hover:text-fg">
            <LayoutGrid className="size-3.5" />All utilities
          </Link>
        </div>
      )}
      <button type="button" aria-label={open ? 'Close utilities' : 'Open utilities'} aria-expanded={open}
        onPointerDown={(e) => { drag.current = { startY: e.clientY, startPct: y, moved: false } }}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setOpen((o) => !o) } }}
        // Solid and accent-marked, so it reads as a control on any background (it was a faint 20px sliver before).
        className={cn('flex h-20 w-7 touch-none flex-col items-center justify-center gap-1 rounded-l-card border border-r-0 border-border-strong bg-surface-2 text-fg shadow-lift transition-[width] hover:w-8 max-md:h-16 max-md:w-6',
          open && 'w-8 max-md:w-7')}>
        <Calculator className="size-4 text-accent" />
        <ChevronLeft className={cn('size-3.5 text-muted transition-transform', open && 'rotate-180')} />
      </button>
    </div>
  )
}
