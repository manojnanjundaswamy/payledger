import { Check, Monitor, Moon, Sun } from 'lucide-react'
import { Segmented } from '@/components/ui/controls'
import { cn } from '@/lib/cn'
import { PALETTES, type PaletteId, type Theme } from '@/state/preferences'

type Mode = Theme['mode']

/** What each palette looks like in light and dark: page, card, accent and ink, for the little screens. */
export const PALETTE_LOOK: Record<PaletteId, Record<'light' | 'dark', { bg: string; card: string; accent: string; ink: string }>> = {
  classic: { light: { bg: '#ffffff', card: '#f6f6f5', accent: '#0a0a0a', ink: '#0a0a0a' }, dark: { bg: '#0a0a0a', card: '#1a1a1a', accent: '#f5f5f4', ink: '#f5f5f4' } },
  bumblebee: { light: { bg: '#fffcf0', card: '#ffffff', accent: '#ffc800', ink: '#14120b' }, dark: { bg: '#0c0b07', card: '#1d1a0e', accent: '#ffd000', ink: '#fff8e1' } },
  ferrari: { light: { bg: '#fff8f4', card: '#ffffff', accent: '#ea5000', ink: '#1a0e09' }, dark: { bg: '#0b0807', card: '#1c1513', accent: '#ff6a1a', ink: '#fff3ec' } },
  sky: { light: { bg: '#f3faff', card: '#ffffff', accent: '#1aa7ec', ink: '#06192b' }, dark: { bg: '#04101b', card: '#0d2133', accent: '#38bdf8', ink: '#eaf6ff' } },
  purple: { light: { bg: '#faf7ff', card: '#ffffff', accent: '#7c3aed', ink: '#1a1030' }, dark: { bg: '#0d0816', card: '#1d142e', accent: '#a78bfa', ink: '#f5efff' } },
}

const STRIPE: Partial<Record<PaletteId, string>> = {
  bumblebee: 'repeating-linear-gradient(-45deg,#ffc800 0 5px,#14120b 5px 10px)',
  ferrari: 'linear-gradient(90deg,#ff8a1f,#ea5000 45%,#d40000)',
  sky: 'linear-gradient(90deg,#7dd3fc,#1aa7ec 50%,#2563eb)',
  purple: 'linear-gradient(90deg,#e9d5ff,#a855f7 40%,#6d28d9 75%,#3b0764)',
}

/** A tiny screen: sidebar, a card with a line of text, and the accent button. */
function Screen({ palette, mode }: { palette: PaletteId; mode: 'light' | 'dark' }) {
  const c = PALETTE_LOOK[palette][mode]
  return (
    <span className="absolute inset-0" style={{ background: c.bg }}>
      {STRIPE[palette] && <span className="absolute inset-x-0 top-0 h-1" style={{ background: STRIPE[palette] }} />}
      <span className="absolute top-2 bottom-2 left-2 w-5 rounded-[3px]" style={{ background: c.card }} />
      <span className="absolute top-3 right-2 left-9 h-8 rounded-[4px] shadow-sm" style={{ background: c.card }}>
        <span className="absolute top-2 left-2 h-1 w-8 rounded-full" style={{ background: c.ink, opacity: 0.75 }} />
        <span className="absolute top-4 left-2 h-1 w-5 rounded-full" style={{ background: c.ink, opacity: 0.35 }} />
      </span>
      <span className="absolute right-2 bottom-2 h-3.5 w-9 rounded-full" style={{ background: c.accent }} />
    </span>
  )
}

/**
 * One choice for how the app looks: a palette (Classic, Bumblebee yellow, Ferrari orange) in light, dark, or
 * matching the device. The cards show the palette in the chosen mode; with "Match device", both halves.
 */
export function ThemePalettePicker({ palette, mode, onChange, className }: {
  palette: PaletteId
  mode: Mode
  onChange: (patch: { palette?: PaletteId; mode?: Mode }) => void
  className?: string
}) {
  return (
    <div className={cn('flex flex-col gap-3', className)}>
      <Segmented label="Light or dark" value={mode} onChange={(m) => onChange({ mode: m })} className="self-start" options={[
        { value: 'light', label: <><Sun />Light</> },
        { value: 'dark', label: <><Moon />Dark</> },
        { value: 'system', label: <><Monitor />Match device</> },
      ]} />
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-5" role="radiogroup" aria-label="Theme palette">
        {PALETTES.map((p) => {
          const on = palette === p.id
          return (
            <button key={p.id} type="button" role="radio" aria-checked={on} onClick={() => onChange({ palette: p.id })} title={p.description}
              data-testid={`palette-${p.id}`}
              className={cn('group flex flex-col gap-2 rounded-card border p-2 text-left transition-all hover:-translate-y-0.5 hover:shadow-card',
                on ? 'border-accent ring-2 ring-ring' : 'border-border hover:border-border-strong')}>
              <span className="relative block h-20 overflow-hidden rounded-[calc(var(--radius-sm)-2px)] border border-black/10">
                {mode === 'system' ? (
                  <>
                    <Screen palette={p.id} mode="light" />
                    {/* Device: light and dark, split on the diagonal. */}
                    <span className="absolute inset-0" style={{ clipPath: 'polygon(100% 0, 100% 100%, 0 100%)' }}><Screen palette={p.id} mode="dark" /></span>
                  </>
                ) : <Screen palette={p.id} mode={mode} />}
              </span>
              <span className="flex items-start justify-between gap-1">
                <span>
                  <span className="block text-sm font-medium">{p.name}</span>
                  <span className="block text-xs text-subtle">{p.description}</span>
                </span>
                {on && <span className="flex size-5 shrink-0 items-center justify-center rounded-full bg-accent text-accent-fg"><Check className="size-3" /></span>}
              </span>
            </button>
          )
        })}
      </div>
    </div>
  )
}
