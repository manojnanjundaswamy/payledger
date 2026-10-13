import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { Maximize2, Minimize2, Minus, X } from 'lucide-react'
import { cn } from '@/lib/cn'
import { useMediaQuery } from '@/hooks/useMediaQuery'
import { TOOLS, type ToolId } from './registry'

/**
 * Floating utility windows, like a tablet's pop-up apps: movable by the title bar, resizable from the corner,
 * minimise to a tray, maximise, close. Several can be open; the one touched last is on top. Where each window was
 * is remembered in this browser only (a convenience, not data).
 */
interface Win { id: ToolId; x: number; y: number; w: number; h: number; minimized: boolean; maximized: boolean; z: number }

interface Windows {
  open: (id: ToolId) => void
  close: (id: ToolId) => void
  isOpen: (id: ToolId) => boolean
}

const Ctx = createContext<Windows | null>(null)
const STORE = 'utility-windows'

export function useUtilityWindows(): Windows {
  const ctx = useContext(Ctx)
  if (!ctx) throw new Error('useUtilityWindows needs <UtilityWindowsProvider>')
  return ctx
}

function load(): Partial<Record<ToolId, Pick<Win, 'x' | 'y' | 'w' | 'h'>>> {
  try { return JSON.parse(localStorage.getItem(STORE) ?? '{}') } catch { return {} }
}

function save(wins: Win[]) {
  try {
    const all = load()
    for (const w of wins) all[w.id] = { x: w.x, y: w.y, w: w.w, h: w.h }
    localStorage.setItem(STORE, JSON.stringify(all))
  } catch { /* storage blocked: windows just open in the default place */ }
}

export function UtilityWindowsProvider({ children }: { children: ReactNode }) {
  const [wins, setWins] = useState<Win[]>([])
  const top = useRef(10)

  const open = useCallback((id: ToolId) => {
    setWins((ws) => {
      const z = ++top.current
      const existing = ws.find((w) => w.id === id)
      if (existing) return ws.map((w) => (w.id === id ? { ...w, minimized: false, z } : w))
      const tool = TOOLS[id]
      const remembered = load()[id]
      const offset = ws.length * 28
      const w = Math.min(remembered?.w ?? tool.size.w, window.innerWidth - 16)
      const h = Math.min(remembered?.h ?? tool.size.h, window.innerHeight - 80)
      const x = clamp(remembered?.x ?? window.innerWidth - w - 96 - offset, 8, window.innerWidth - w - 8)
      const y = clamp(remembered?.y ?? 80 + offset, 64, window.innerHeight - 48)
      return [...ws, { id, x, y, w, h, minimized: false, maximized: false, z }]
    })
  }, [])
  const close = useCallback((id: ToolId) => setWins((ws) => ws.filter((w) => w.id !== id)), [])
  const update = useCallback((id: ToolId, patch: Partial<Win>) => {
    setWins((ws) => {
      const next = ws.map((w) => (w.id === id ? { ...w, ...patch } : w))
      if ('x' in patch || 'w' in patch) save(next)
      return next
    })
  }, [])
  const focus = useCallback((id: ToolId) => update(id, { z: ++top.current }), [update])
  const value = useMemo<Windows>(() => ({ open, close, isOpen: (id) => wins.some((w) => w.id === id) }), [open, close, wins])

  return (
    <Ctx.Provider value={value}>
      {children}
      {createPortal(
        <>
          {wins.map((w) => <Window key={w.id} win={w} update={update} focus={focus} close={close} />)}
          <Tray wins={wins.filter((w) => w.minimized)} restore={(id) => update(id, { minimized: false, z: ++top.current })} close={close} />
        </>,
        document.body,
      )}
    </Ctx.Provider>
  )
}

function Window({ win, update, focus, close }: {
  win: Win; update: (id: ToolId, p: Partial<Win>) => void; focus: (id: ToolId) => void; close: (id: ToolId) => void
}) {
  const tool = TOOLS[win.id]
  const phone = !useMediaQuery('(min-width: 640px)')
  const full = win.maximized || phone
  const drag = useRef<{ dx: number; dy: number; resize: boolean; w: number; h: number; sx: number; sy: number } | null>(null)

  useEffect(() => {
    const move = (e: PointerEvent) => {
      const d = drag.current
      if (!d) return
      if (d.resize) {
        update(win.id, { w: clamp(d.w + e.clientX - d.sx, 280, window.innerWidth - win.x - 8), h: clamp(d.h + e.clientY - d.sy, 200, window.innerHeight - win.y - 8) })
      } else {
        update(win.id, { x: clamp(e.clientX - d.dx, 8 - win.w + 120, window.innerWidth - 120), y: clamp(e.clientY - d.dy, 56, window.innerHeight - 44) })
      }
    }
    const up = () => { drag.current = null }
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', up)
    return () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up) }
  }, [update, win.id, win.x, win.y, win.w])

  // A minimised window stays mounted (hidden), so what was in it (a half-done calculation) is still there when restored.
  const style = full ? { zIndex: 60 + win.z } : { left: win.x, top: win.y, width: win.w, height: win.h, zIndex: 60 + win.z }
  return (
    <section role="dialog" aria-label={tool.name} data-testid={`window-${win.id}`} style={style} hidden={win.minimized}
      onPointerDown={() => focus(win.id)}
      className={cn('fixed flex flex-col overflow-hidden border border-border bg-surface shadow-lift',
        full ? (phone ? 'inset-x-0 bottom-0 top-16 rounded-t-card' : 'inset-4 top-20 rounded-card') : 'rounded-card')}>
      <header className={cn('flex h-10 shrink-0 select-none items-center gap-2 border-b border-border bg-surface-2 pl-3 pr-1', !full && 'cursor-move touch-none')}
        onPointerDown={(e) => {
          if (full || (e.target as HTMLElement).closest('button')) return
          drag.current = { dx: e.clientX - win.x, dy: e.clientY - win.y, resize: false, w: win.w, h: win.h, sx: e.clientX, sy: e.clientY }
        }}
        onDoubleClick={() => !phone && update(win.id, { maximized: !win.maximized })}>
        <tool.icon className="size-4 text-muted" />
        <h2 className="flex-1 truncate text-sm font-medium">{tool.name}</h2>
        <WinButton label="Minimise" onClick={() => update(win.id, { minimized: true })}><Minus /></WinButton>
        {!phone && (
          <WinButton label={win.maximized ? 'Restore' : 'Maximise'} onClick={() => update(win.id, { maximized: !win.maximized })}>
            {win.maximized ? <Minimize2 /> : <Maximize2 />}
          </WinButton>
        )}
        <WinButton label="Close" onClick={() => close(win.id)}><X /></WinButton>
      </header>
      <div className="min-h-0 flex-1 overflow-auto p-4"><tool.component /></div>
      {!full && (
        <div aria-hidden className="absolute right-0 bottom-0 size-4 cursor-se-resize touch-none"
          onPointerDown={(e) => { e.stopPropagation(); drag.current = { dx: 0, dy: 0, resize: true, w: win.w, h: win.h, sx: e.clientX, sy: e.clientY } }}>
          <svg viewBox="0 0 16 16" className="size-4 text-subtle"><path d="M15 6 6 15M15 11l-4 4" stroke="currentColor" strokeWidth="1.5" /></svg>
        </div>
      )}
    </section>
  )
}

function WinButton({ label, onClick, children }: { label: string; onClick: () => void; children: ReactNode }) {
  return (
    <button type="button" aria-label={label} title={label} onClick={onClick}
      className="flex size-8 items-center justify-center rounded-control text-muted hover:bg-surface-3 hover:text-fg [&_svg]:size-4">
      {children}
    </button>
  )
}

/** Minimised windows wait here, bottom left (above the phone tab bar). */
function Tray({ wins, restore, close }: { wins: Win[]; restore: (id: ToolId) => void; close: (id: ToolId) => void }) {
  if (wins.length === 0) return null
  return (
    <div className="fixed bottom-20 left-4 z-[70] flex flex-wrap gap-2 md:bottom-4 md:left-[calc(var(--sidebar-w,76px)+16px)]" aria-label="Minimised utilities">
      {wins.map((w) => {
        const tool = TOOLS[w.id]
        return (
          <span key={w.id} className="flex items-center rounded-full border border-border bg-surface shadow-lift">
            <button type="button" onClick={() => restore(w.id)} className="flex items-center gap-2 py-1.5 pr-1 pl-3 text-sm" aria-label={`Restore ${tool.name}`}>
              <tool.icon className="size-4 text-muted" />{tool.name}
            </button>
            <button type="button" onClick={() => close(w.id)} aria-label={`Close ${tool.name}`} className="rounded-full p-1.5 text-muted hover:text-fg"><X className="size-3.5" /></button>
          </span>
        )
      })}
    </div>
  )
}

function clamp(v: number, min: number, max: number) {
  return Math.max(min, Math.min(max, v))
}
