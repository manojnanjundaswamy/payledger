import { useEffect } from 'react'
import { Command } from 'cmdk'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { useNavigate } from '@tanstack/react-router'
import { Eye, EyeOff, LayoutGrid, LogOut, Monitor, Moon, Palette, Pencil, Plus, Search, Sun } from 'lucide-react'
import { WIDGETS } from '@/dashboard/registry'
import { ACCENT_PRESETS, type WidgetType } from '@/state/preferences'
import { usePreferences } from '@/state/PreferencesProvider'
import { signOut } from '@/state/session'
import { dashboardEvents } from '@/dashboard/events'
import { NAV } from './nav'

export function useCommandPaletteShortcut(toggle: () => void) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        toggle()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [toggle])
}

export function CommandPalette({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const navigate = useNavigate()
  const { prefs, dispatch } = usePreferences()
  const run = (fn: () => void) => () => {
    onOpenChange(false)
    fn()
  }
  const toDashboard = (fn: () => void) => run(() => {
    void navigate({ to: '/dashboard' }).then(() => setTimeout(fn, 50))
  })

  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-40 bg-[var(--overlay)] backdrop-blur-[2px] data-[state=open]:animate-[fade-in_120ms_ease-out]" />
        <DialogPrimitive.Content
          aria-label="Command palette"
          className="fixed top-[12dvh] left-1/2 z-50 w-[min(94vw,600px)] -translate-x-1/2 overflow-hidden rounded-card border border-border bg-surface shadow-lift data-[state=open]:animate-[pop-in_140ms_cubic-bezier(.2,.8,.2,1)]"
        >
          <DialogPrimitive.Title className="sr-only">Command palette</DialogPrimitive.Title>
          <DialogPrimitive.Description className="sr-only">Search pages and actions</DialogPrimitive.Description>
          <Command loop className="[&_[cmdk-group-heading]]:px-3 [&_[cmdk-group-heading]]:pt-3 [&_[cmdk-group-heading]]:pb-1 [&_[cmdk-group-heading]]:text-[0.6875rem] [&_[cmdk-group-heading]]:font-medium [&_[cmdk-group-heading]]:tracking-wider [&_[cmdk-group-heading]]:text-subtle [&_[cmdk-group-heading]]:uppercase">
            <div className="flex items-center gap-3 border-b border-border px-4">
              <Search className="size-4 text-subtle" />
              <Command.Input autoFocus placeholder="Type a command or search…" className="h-14 flex-1 bg-transparent text-[0.9375rem] outline-none placeholder:text-subtle" />
            </div>
            <Command.List className="max-h-[min(60dvh,420px)] overflow-y-auto p-2">
              <Command.Empty className="py-10 text-center text-sm text-muted">Nothing found.</Command.Empty>
              <Command.Group heading="Go to">
                {NAV.map((n) => (
                  <Item key={n.to} icon={<n.icon />} onSelect={run(() => navigate({ to: n.to }))}>{n.label}</Item>
                ))}
              </Command.Group>
              <Command.Group heading="Dashboard">
                <Item icon={<Pencil />} onSelect={toDashboard(() => dashboardEvents.emit('edit'))}>Customize dashboard</Item>
                <Item icon={<LayoutGrid />} onSelect={toDashboard(() => dashboardEvents.emit('newDashboard'))}>New dashboard</Item>
                {(Object.keys(WIDGETS) as WidgetType[]).map((type) => (
                  <Item key={type} icon={<Plus />} keywords={['widget', 'add']} onSelect={toDashboard(() => dashboardEvents.emit('addWidget', type))}>
                    Add widget: {WIDGETS[type].name}
                  </Item>
                ))}
                <Item icon={prefs.sampleData ? <EyeOff /> : <Eye />} onSelect={run(() => dispatch({ type: 'sampleData', value: !prefs.sampleData }))}>
                  {prefs.sampleData ? 'Hide sample data' : 'Show sample data'}
                </Item>
              </Command.Group>
              <Command.Group heading="Appearance">
                <Item icon={<Sun />} onSelect={run(() => dispatch({ type: 'theme', patch: { mode: 'light' } }))}>Light theme</Item>
                <Item icon={<Moon />} onSelect={run(() => dispatch({ type: 'theme', patch: { mode: 'dark' } }))}>Dark theme</Item>
                <Item icon={<Monitor />} onSelect={run(() => dispatch({ type: 'theme', patch: { mode: 'system' } }))}>Theme: match my device</Item>
                {ACCENT_PRESETS.map((p) => (
                  <Item key={p.name} keywords={['accent', 'colour', 'color']} icon={<Swatch color={p.value} />}
                    onSelect={run(() => dispatch({ type: 'theme', patch: { accent: p.value } }))}>
                    Accent: {p.name}
                  </Item>
                ))}
                <Item icon={<Palette />} onSelect={run(() => navigate({ to: '/settings', search: { tab: 'appearance' } }))}>Open theme builder</Item>
              </Command.Group>
              <Command.Group heading="Account">
                <Item icon={<LogOut />} onSelect={run(() => void signOut())}>Sign out</Item>
              </Command.Group>
            </Command.List>
          </Command>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}

function Item({ children, icon, onSelect, keywords }: { children: React.ReactNode; icon: React.ReactNode; onSelect: () => void; keywords?: string[] }) {
  return (
    <Command.Item
      onSelect={onSelect}
      keywords={keywords}
      className="flex h-10 cursor-pointer items-center gap-3 rounded-control px-3 text-sm text-muted data-[selected=true]:bg-surface-2 data-[selected=true]:text-fg [&_svg]:size-4"
    >
      {icon}
      {children}
    </Command.Item>
  )
}

function Swatch({ color }: { color: string | null }) {
  return <span className="size-4 rounded-full border border-border" style={{ background: color ?? 'linear-gradient(135deg, var(--fg) 50%, var(--bg) 50%)' }} />
}
