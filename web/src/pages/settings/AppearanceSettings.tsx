import { useState } from 'react'
import { Check, Monitor, Moon, Pipette, Sun } from 'lucide-react'
import { Badge } from '@/components/ui/card'
import { Input, Segmented } from '@/components/ui/controls'
import { Progress } from '@/dashboard/charts'
import { accentFor } from '@/lib/color'
import { cn } from '@/lib/cn'
import { ACCENT_PRESETS, type Theme } from '@/state/preferences'
import { usePreferences } from '@/state/PreferencesProvider'
import { themeVars } from '@/theme/engine'
import { Field, Section } from './parts'

export function AppearanceSettings() {
  const { prefs, dispatch } = usePreferences()
  const theme = prefs.theme
  const set = (patch: Partial<Theme>) => dispatch({ type: 'theme', patch })

  return (
    <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_380px]">
      <div className="flex flex-col gap-8">
        <Section title="Theme" description="Black and white by default. Make it yours: every change applies instantly and is saved to your account.">
          <Field label="Mode">
            <Segmented label="Mode" value={theme.mode} onChange={(mode) => set({ mode })} options={[
              { value: 'light', label: <><Sun />Light</> },
              { value: 'dark', label: <><Moon />Dark</> },
              { value: 'system', label: <><Monitor />Device</> },
            ]} />
          </Field>
          <Field label="Accent colour" hint="Used for buttons, highlights and focus. Pale or very dark colours are deepened or lightened just enough to stay readable.">
            <AccentPicker value={theme.accent} onChange={(accent) => set({ accent })} />
          </Field>
        </Section>

        <Section title="Layout" description="Shape and spacing of everything on screen.">
          <Field label="Corners">
            <Segmented label="Corners" value={theme.radius} onChange={(radius) => set({ radius })} options={[
              { value: 'sharp', label: <><span className="size-3.5 rounded-[2px] border-2 border-current" />Sharp</> },
              { value: 'soft', label: <><span className="size-3.5 rounded-[5px] border-2 border-current" />Soft</> },
              { value: 'round', label: <><span className="size-3.5 rounded-full border-2 border-current" />Round</> },
            ]} />
          </Field>
          <Field label="Density" hint="Compact fits more on screen; comfortable is easier on the eyes.">
            <Segmented label="Density" value={theme.density} onChange={(density) => set({ density })} options={[
              { value: 'comfortable', label: 'Comfortable' }, { value: 'compact', label: 'Compact' },
            ]} />
          </Field>
          <Field label="Text size">
            <Segmented label="Text size" value={theme.textSize} onChange={(textSize) => set({ textSize })} options={[
              { value: 'sm', label: <span className="text-xs">Aa</span>, title: 'Small' },
              { value: 'md', label: <span className="text-sm">Aa</span>, title: 'Default' },
              { value: 'lg', label: <span className="text-base">Aa</span>, title: 'Large' },
            ]} />
          </Field>
          <Field label="Motion" hint="Animations make changes easy to follow. Reduce them if they distract you.">
            <Segmented label="Motion" value={theme.motion} onChange={(motion) => set({ motion })} options={[
              { value: 'system', label: 'Match device' }, { value: 'reduced', label: 'Reduced' }, { value: 'full', label: 'Full' },
            ]} />
          </Field>
        </Section>
      </div>

      <div className="lg:sticky lg:top-24 lg:self-start">
        <div className="mb-2 text-sm font-medium">Preview</div>
        <div className="grid grid-cols-2 gap-3 lg:grid-cols-1">
          <ThemePreview theme={theme} mode="light" />
          <ThemePreview theme={theme} mode="dark" />
        </div>
      </div>
    </div>
  )
}

function AccentPicker({ value, onChange }: { value: string | null; onChange: (v: string | null) => void }) {
  const [hex, setHex] = useState(value ?? '')
  const adjusted = value && (accentFor(value, '#ffffff') !== value || accentFor(value, '#0a0a0a') !== value)
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap gap-2" role="radiogroup" aria-label="Accent colour">
        {ACCENT_PRESETS.map((p) => {
          const selected = value === p.value
          return (
            <button key={p.name} role="radio" aria-checked={selected} aria-label={p.name} title={p.name}
              onClick={() => { onChange(p.value); setHex(p.value ?? '') }}
              className={cn('relative flex size-10 items-center justify-center rounded-full border border-border transition-transform hover:scale-110', selected && 'ring-2 ring-fg ring-offset-2 ring-offset-bg')}
              style={{ background: p.value ?? 'linear-gradient(135deg, #0a0a0a 50%, #ffffff 50%)' }}>
              {selected && <Check className="size-4 drop-shadow" style={{ color: p.value ? '#fff' : '#888' }} strokeWidth={3} />}
            </button>
          )
        })}
        <label className={cn('relative flex size-10 cursor-pointer items-center justify-center overflow-hidden rounded-full border border-dashed border-border-strong hover:scale-110 transition-transform',
          value && !ACCENT_PRESETS.some((p) => p.value === value) && 'ring-2 ring-fg ring-offset-2 ring-offset-bg')}
          title="Pick any colour" style={value && !ACCENT_PRESETS.some((p) => p.value === value) ? { background: value } : undefined}>
          <Pipette className="size-4 text-muted mix-blend-difference" />
          <input type="color" className="absolute inset-0 cursor-pointer opacity-0" aria-label="Pick any colour"
            value={value ?? '#4f46e5'} onChange={(e) => { onChange(e.target.value); setHex(e.target.value) }} />
        </label>
      </div>
      <div className="flex items-center gap-2">
        <Input className="w-32 font-mono" placeholder="#4f46e5" value={hex} aria-label="Accent hex value"
          onChange={(e) => {
            setHex(e.target.value)
            if (/^#[0-9a-fA-F]{6}$/.test(e.target.value)) onChange(e.target.value.toLowerCase())
          }} />
        {adjusted && <Badge tone="accent">Adjusted per mode for readability</Badge>}
      </div>
    </div>
  )
}

/** A miniature of the app in one mode, rendered with that mode's tokens and this theme's variables. */
function ThemePreview({ theme, mode }: { theme: Theme; mode: 'light' | 'dark' }) {
  const vars = themeVars(theme, mode)
  const style = Object.fromEntries(Object.entries(vars).filter(([k]) => k.startsWith('--'))) as React.CSSProperties
  return (
    <div data-theme={mode} style={style} className="overflow-hidden rounded-card border border-border bg-bg text-fg" aria-label={`${mode} preview`}>
      <div className="flex">
        <div className="hidden w-24 shrink-0 flex-col gap-1 border-r border-border bg-surface p-2 sm:flex">
          <div className="mb-1 h-2 w-12 rounded bg-fg" />
          <div className="rounded-control bg-accent-soft px-2 py-1 text-[0.625rem] font-medium">Dashboard</div>
          <div className="px-2 py-1 text-[0.625rem] text-muted">Budgets</div>
          <div className="px-2 py-1 text-[0.625rem] text-muted">Groups</div>
        </div>
        <div className="flex flex-1 flex-col gap-2 p-pad text-xs">
          <div className="flex items-center justify-between">
            <span className="font-semibold capitalize">{mode}</span>
            <span className="rounded-control bg-accent px-2 py-1 text-[0.625rem] font-medium text-accent-fg">Add</span>
          </div>
          <div className="rounded-card border border-border bg-surface p-pad shadow-card">
            <div className="text-[0.625rem] text-muted">Safe to spend</div>
            <div className="text-base font-semibold">₹15,450</div>
            <div className="mt-2"><Progress value={0.62} label="preview" /></div>
          </div>
        </div>
      </div>
    </div>
  )
}
