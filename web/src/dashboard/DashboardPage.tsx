import { useEffect, useMemo, useState } from 'react'
import {
  closestCenter, DndContext, DragOverlay, KeyboardSensor, PointerSensor, TouchSensor, useSensor, useSensors,
  type DragEndEvent, type DragStartEvent, type Announcements,
} from '@dnd-kit/core'
import { rectSortingStrategy, SortableContext, sortableKeyboardCoordinates, useSortable } from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { AnimatePresence, motion } from 'motion/react'
import { Check, Eye, LayoutGrid, MoreHorizontal, PencilLine, Plus, RotateCcw, Sparkles, Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input, Switch } from '@/components/ui/controls'
import { Dialog, Menu, MenuContent, MenuItem, MenuSeparator, MenuTrigger } from '@/components/ui/overlay'
import { cn } from '@/lib/cn'
import { formatMoney, formatMoneyShort, formatMoneyWhole } from '@/lib/money'
import { isTyping } from '@/layout/AppShell'
import { DASHBOARD_TEMPLATES, type Dashboard, type TemplateKey, type Widget, type WidgetType } from '@/state/preferences'
import { usePreferences, useUndoable } from '@/state/PreferencesProvider'
import { useProfile } from '@/state/profile'
import { WidgetEnvContext, type WidgetEnv } from './context'
import { dashboardEvents } from './events'
import { WidgetCard } from './WidgetCard'
import { WIDGETS } from './widgets'

export function DashboardPage() {
  const { prefs, dispatch } = usePreferences()
  const { data: profile } = useProfile()
  const [editing, setEditing] = useState(false)
  const [library, setLibrary] = useState(false)
  const [newDashboard, setNewDashboard] = useState(false)
  const dashboard = prefs.dashboards.find((d) => d.id === prefs.activeDashboard) ?? prefs.dashboards[0]!

  // Driven from the command palette.
  useEffect(() => {
    const offs = [
      dashboardEvents.on('edit', () => setEditing(true)),
      dashboardEvents.on('newDashboard', () => setNewDashboard(true)),
      dashboardEvents.on('addWidget', (type) => {
        dispatch({ type: 'addWidget', dashboard: prefs.activeDashboard, widget: type, size: WIDGETS[type].defaultSize })
        toast.success(`Added “${WIDGETS[type].name}”`)
      }),
    ]
    return () => offs.forEach((off) => off())
  }, [dispatch, prefs.activeDashboard])

  // "e" toggles customize mode; Escape leaves it.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (isTyping(e) || e.metaKey || e.ctrlKey || e.altKey) return
      if (e.key === 'e') setEditing((v) => !v)
      if (e.key === 'Escape' && editing && !document.querySelector('[role="dialog"]')) setEditing(false)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [editing])

  const env = useMemo<WidgetEnv | null>(() => {
    if (!profile) return null
    const { currency, locale } = profile.settings
    return {
      sample: prefs.sampleData,
      profile,
      prefs,
      money: (minor, opts = {}) =>
        opts.compact ? formatMoneyShort(minor, currency, locale)
          : opts.whole ? formatMoneyWhole(minor, currency, locale) : formatMoney(minor, currency, locale, opts),
    }
  }, [profile, prefs])
  if (!env || !profile) return null

  return (
    <WidgetEnvContext.Provider value={env}>
      <div className="flex flex-col gap-5">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <p className="text-sm text-muted">{new Date().toLocaleDateString(profile.settings.locale, { weekday: 'long', day: 'numeric', month: 'long' })}</p>
            <h2 className="mt-0.5 text-2xl font-semibold tracking-tight sm:text-[1.75rem]">{greeting()}, {profile.displayName.split(' ')[0]}</h2>
          </div>
          <div className="flex items-center gap-2">
            {editing && (
              <Button variant="outline" onClick={() => setLibrary(true)}><Plus />Add widget</Button>
            )}
            <Button variant={editing ? 'primary' : 'secondary'} onClick={() => setEditing((v) => !v)} aria-pressed={editing}>
              {editing ? <><Check />Done</> : <><LayoutGrid />Customize</>}
            </Button>
          </div>
        </div>

        <DashboardTabs dashboard={dashboard} onNew={() => setNewDashboard(true)} />

        <AnimatePresence initial={false}>
          {editing && (
            <motion.div initial={{ opacity: 0, height: 0 }} animate={{ opacity: 1, height: 'auto' }} exit={{ opacity: 0, height: 0 }} className="overflow-hidden">
              <div className="flex flex-wrap items-center gap-x-4 gap-y-2 rounded-card border border-dashed border-border-strong bg-surface-2 px-4 py-3 text-sm text-muted">
                <Sparkles className="size-4 text-accent" />
                <span>Drag widgets by their handle to rearrange. Pick a size, rename by double-clicking a title, or remove what you don't need.</span>
                <span className="text-xs text-subtle">Keyboard: focus a handle, press Space, move with arrow keys, Space to drop.</span>
              </div>
            </motion.div>
          )}
        </AnimatePresence>

        {prefs.sampleData && !editing && (
          <div className="flex items-center gap-3 rounded-card bg-accent-soft px-4 py-3 text-sm">
            <Eye className="size-4 shrink-0" />
            <span className="flex-1">
              <span className="sm:hidden">Showing <strong className="font-semibold">sample data</strong></span>
              <span className="max-sm:hidden">You're looking at <strong className="font-semibold">sample data</strong> so you can see what each widget does. Your own numbers replace it once you add accounts.</span>
            </span>
            <label className="flex shrink-0 items-center gap-2 text-muted"><span className="max-sm:sr-only">Show sample data</span> <Switch checked label="Show sample data" onCheckedChange={(v) => dispatch({ type: 'sampleData', value: v })} /></label>
          </div>
        )}

        <WidgetGrid dashboard={dashboard} editing={editing} onAdd={() => { setEditing(true); setLibrary(true) }} />
      </div>

      <WidgetLibrary open={library} onOpenChange={setLibrary} dashboard={dashboard} />
      <NewDashboardDialog open={newDashboard} onOpenChange={setNewDashboard} />
    </WidgetEnvContext.Provider>
  )
}

function greeting() {
  const h = new Date().getHours()
  return h < 5 ? 'Good night' : h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening'
}

function WidgetGrid({ dashboard, editing, onAdd }: { dashboard: Dashboard; editing: boolean; onAdd: () => void }) {
  const { dispatch } = usePreferences()
  const [activeId, setActiveId] = useState<string | null>(null)
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
    // Press-and-hold on touch screens, so a normal swipe still scrolls the page.
    useSensor(TouchSensor, { activationConstraint: { delay: 180, tolerance: 6 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )
  const nameOf = (id: string | number) => {
    const w = dashboard.widgets.find((x) => x.id === id)
    return w ? (w.title ?? WIDGETS[w.type].name) : 'widget'
  }
  const announcements: Announcements = {
    onDragStart: ({ active }) => `Picked up ${nameOf(active.id)}.`,
    onDragOver: ({ active, over }) => {
      if (!over) return `${nameOf(active.id)} is not over a position.`
      // Right after pick-up it is "over" itself; say so plainly instead of "X is over X".
      return over.id === active.id ? `${nameOf(active.id)} is in its current place.` : `${nameOf(active.id)} is over ${nameOf(over.id)}.`
    },
    onDragEnd: ({ active, over }) => (over ? `Dropped ${nameOf(active.id)} at ${nameOf(over.id)}'s place.` : `Dropped ${nameOf(active.id)}.`),
    onDragCancel: ({ active }) => `Moving ${nameOf(active.id)} was cancelled.`,
  }

  if (dashboard.widgets.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center gap-4 rounded-card border border-dashed border-border-strong px-6 py-20 text-center">
        <div className="flex size-14 items-center justify-center rounded-full bg-accent-soft"><LayoutGrid className="size-6" /></div>
        <div>
          <h3 className="font-semibold">An empty canvas</h3>
          <p className="mt-1 max-w-sm text-sm text-muted">Add the widgets you care about, size them, and arrange them the way you think about your money.</p>
        </div>
        <Button variant="primary" onClick={onAdd}><Plus />Add widgets</Button>
      </div>
    )
  }

  const active = dashboard.widgets.find((w) => w.id === activeId)
  return (
    <DndContext
      sensors={sensors}
      collisionDetection={closestCenter}
      accessibility={{ announcements }}
      onDragStart={(e: DragStartEvent) => setActiveId(String(e.active.id))}
      onDragCancel={() => setActiveId(null)}
      onDragEnd={(e: DragEndEvent) => {
        setActiveId(null)
        if (e.over && e.active.id !== e.over.id) {
          dispatch({ type: 'moveWidget', dashboard: dashboard.id, from: String(e.active.id), to: String(e.over.id) })
        }
      }}
    >
      <SortableContext items={dashboard.widgets.map((w) => w.id)} strategy={rectSortingStrategy}>
        <div className="grid grid-cols-1 gap-gap sm:grid-cols-2 xl:grid-cols-4" data-testid="widget-grid">
          {dashboard.widgets.map((w) => (
            <SortableWidget key={w.id} widget={w} dashboardId={dashboard.id} editing={editing} />
          ))}
        </div>
      </SortableContext>
      <DragOverlay dropAnimation={{ duration: 220, easing: 'cubic-bezier(.2,.8,.2,1)' }}>
        {active ? <WidgetCard widget={active} dashboardId={dashboard.id} editing overlay className="h-full" /> : null}
      </DragOverlay>
    </DndContext>
  )
}

function SortableWidget({ widget, dashboardId, editing }: { widget: Widget; dashboardId: string; editing: boolean }) {
  const { setNodeRef, attributes, listeners, transform, transition, isDragging } = useSortable({ id: widget.id, disabled: !editing })
  return (
    <WidgetCard
      ref={setNodeRef}
      widget={widget}
      dashboardId={dashboardId}
      editing={editing}
      dragging={isDragging}
      style={{ transform: CSS.Translate.toString(transform), transition }}
      handleProps={{ ...attributes, ...listeners }}
      data-widget-id={widget.id}
      data-widget-type={widget.type}
    />
  )
}

function DashboardTabs({ dashboard, onNew }: { dashboard: Dashboard; onNew: () => void }) {
  const { prefs, dispatch } = usePreferences()
  const undoable = useUndoable()
  const [renaming, setRenaming] = useState<string | null>(null)
  return (
    <div className="flex items-center gap-2 border-b border-border">
      <div role="tablist" aria-label="Dashboards" className="-mb-px flex min-w-0 flex-1 items-center gap-1 overflow-x-auto [scrollbar-width:none]">
        {prefs.dashboards.map((d) => {
          const selected = d.id === dashboard.id
          return renaming === d.id ? (
            <Input
              key={d.id}
              autoFocus
              defaultValue={d.name}
              maxLength={40}
              aria-label="Dashboard name"
              className="mb-1 h-8 w-40"
              onBlur={(e) => { dispatch({ type: 'renameDashboard', id: d.id, name: e.target.value }); setRenaming(null) }}
              onKeyDown={(e) => {
                if (e.key === 'Enter') (e.target as HTMLInputElement).blur()
                if (e.key === 'Escape') setRenaming(null)
              }}
            />
          ) : (
            <button
              key={d.id}
              role="tab"
              aria-selected={selected}
              onClick={() => dispatch({ type: 'selectDashboard', id: d.id })}
              onDoubleClick={() => setRenaming(d.id)}
              className={cn('relative shrink-0 px-3 pt-1 pb-3 text-sm font-medium whitespace-nowrap transition-colors', selected ? 'text-fg' : 'text-muted hover:text-fg')}
            >
              {d.name}
              {selected && <motion.span layoutId="dashboard-tab" className="absolute inset-x-2 bottom-0 h-0.5 rounded-full bg-accent" />}
            </button>
          )
        })}
        <button onClick={onNew} className="mb-2 ml-1 flex size-7 shrink-0 items-center justify-center rounded-control text-muted hover:bg-surface-2 hover:text-fg" aria-label="New dashboard">
          <Plus className="size-4" />
        </button>
      </div>
      <Menu>
        <MenuTrigger asChild>
          <Button variant="ghost" size="icon-sm" className="mb-2" aria-label="Dashboard options"><MoreHorizontal /></Button>
        </MenuTrigger>
        <MenuContent>
          <MenuItem icon={<PencilLine />} onSelect={() => setRenaming(dashboard.id)}>Rename “{dashboard.name}”</MenuItem>
          <MenuItem icon={<RotateCcw />} onSelect={() => undoable({ type: 'resetDashboard', id: dashboard.id }, `Reset “${dashboard.name}” to the default layout`)}>Reset layout</MenuItem>
          <MenuItem icon={<Eye />} onSelect={() => dispatch({ type: 'sampleData', value: !prefs.sampleData })}>{prefs.sampleData ? 'Hide' : 'Show'} sample data</MenuItem>
          {prefs.dashboards.length > 1 && (
            <>
              <MenuSeparator />
              <MenuItem icon={<Trash2 />} destructive onSelect={() => undoable({ type: 'removeDashboard', id: dashboard.id }, `Deleted “${dashboard.name}”`)}>Delete dashboard</MenuItem>
            </>
          )}
        </MenuContent>
      </Menu>
    </div>
  )
}

function WidgetLibrary({ open, onOpenChange, dashboard }: { open: boolean; onOpenChange: (open: boolean) => void; dashboard: Dashboard }) {
  const { dispatch } = usePreferences()
  const add = (type: WidgetType) => {
    dispatch({ type: 'addWidget', dashboard: dashboard.id, widget: type, size: WIDGETS[type].defaultSize })
    toast.success(`Added “${WIDGETS[type].name}”`, { description: 'It is at the top of your dashboard.' })
    onOpenChange(false)
  }
  return (
    <Dialog open={open} onOpenChange={onOpenChange} title="Add a widget" description={`To “${dashboard.name}”. You can add the same widget more than once, e.g. two notes.`}>
      <div className="grid grid-cols-1 gap-2 p-pad sm:grid-cols-2">
        {(Object.keys(WIDGETS) as WidgetType[]).map((type) => {
          const meta = WIDGETS[type]
          const count = dashboard.widgets.filter((w) => w.type === type).length
          return (
            <button key={type} onClick={() => add(type)}
              className="group flex items-start gap-3 rounded-card border border-border p-3 text-left transition-all hover:-translate-y-0.5 hover:border-border-strong hover:shadow-card">
              <span className="flex size-9 shrink-0 items-center justify-center rounded-control bg-surface-2 transition-colors group-hover:bg-accent group-hover:text-accent-fg"><meta.icon className="size-4" /></span>
              <span className="min-w-0">
                <span className="flex items-center gap-2 text-sm font-medium">{meta.name}{count > 0 && <span className="text-xs font-normal text-subtle">on board ×{count}</span>}</span>
                <span className="mt-0.5 block text-xs text-muted">{meta.description}</span>
              </span>
            </button>
          )
        })}
      </div>
    </Dialog>
  )
}

const TEMPLATE_INFO: Record<TemplateKey, string> = {
  overview: 'Safe to spend, budgets, spending, cash flow and more',
  household: 'The family budget, bills and goals',
  splitting: 'Group balances, recent activity and a note',
  blank: 'Start empty and add exactly what you want',
}

function NewDashboardDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const { prefs, dispatch } = usePreferences()
  const [template, setTemplate] = useState<TemplateKey>('blank')
  const [name, setName] = useState('')
  const full = prefs.dashboards.length >= 12
  return (
    <Dialog open={open} onOpenChange={onOpenChange} title="New dashboard" description="Make one for each part of your life: home, a trip, your business.">
      <form
        className="flex flex-col gap-4 p-pad"
        onSubmit={(e) => {
          e.preventDefault()
          dispatch({ type: 'addDashboard', template, name })
          setName('')
          onOpenChange(false)
        }}
      >
        <Input autoFocus placeholder={DASHBOARD_TEMPLATES[template].name} value={name} maxLength={40} onChange={(e) => setName(e.target.value)} aria-label="Dashboard name" />
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2" role="radiogroup" aria-label="Start from">
          {(Object.keys(DASHBOARD_TEMPLATES) as TemplateKey[]).map((key) => (
            <button type="button" key={key} role="radio" aria-checked={template === key} onClick={() => setTemplate(key)}
              className={cn('rounded-card border p-3 text-left transition-colors', template === key ? 'border-accent bg-accent-soft' : 'border-border hover:border-border-strong')}>
              <div className="text-sm font-medium">{DASHBOARD_TEMPLATES[key].name}</div>
              <div className="mt-0.5 text-xs text-muted">{TEMPLATE_INFO[key]}</div>
            </button>
          ))}
        </div>
        {full && <p className="text-sm text-critical">You have 12 dashboards, the maximum. Delete one to add another.</p>}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button variant="primary" type="submit" disabled={full}>Create dashboard</Button>
        </div>
      </form>
    </Dialog>
  )
}
