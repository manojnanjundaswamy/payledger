import { forwardRef, useState, type CSSProperties, type HTMLAttributes } from 'react'
import { motion } from 'motion/react'
import { GripVertical, MoreHorizontal, PencilLine, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/card'
import { Input, Segmented } from '@/components/ui/controls'
import { Dialog, Menu, MenuContent, MenuItem, MenuLabel, MenuSeparator, MenuTrigger } from '@/components/ui/overlay'
import { cn } from '@/lib/cn'
import { WIDGET_SIZES, type Widget, type WidgetSize } from '@/state/preferences'
import { useUndoable, usePreferences } from '@/state/PreferencesProvider'
import { useWidgetEnv } from './context'
import { WIDGETS } from './widgets'

export const SIZE_CLASS: Record<WidgetSize, string> = {
  s: 'col-span-1',
  m: 'sm:col-span-2 xl:col-span-2',
  l: 'sm:col-span-2 xl:col-span-3',
  xl: 'sm:col-span-2 xl:col-span-4',
}
const SIZE_LABEL: Record<WidgetSize, string> = { s: 'Small', m: 'Medium', l: 'Large', xl: 'Full width' }

interface Props extends HTMLAttributes<HTMLDivElement> {
  widget: Widget
  dashboardId: string
  editing: boolean
  dragging?: boolean
  overlay?: boolean
  handleProps?: HTMLAttributes<HTMLButtonElement>
  style?: CSSProperties
  /** Its place on the dashboard: widgets arrive one after another. */
  index?: number
}

export const WidgetCard = forwardRef<HTMLDivElement, Props>(function WidgetCard(
  { widget, dashboardId, editing, dragging, overlay, handleProps, className, style, index = 0, ...rest }, ref,
) {
  const meta = WIDGETS[widget.type]
  const env = useWidgetEnv()
  const Body = meta.component
  const title = widget.title ?? meta.name
  return (
    <div
      ref={ref}
      style={style}
      className={cn(SIZE_CLASS[widget.size], 'min-w-0', dragging && 'opacity-30', className)}
      {...rest}
    >
      <motion.section
        initial={overlay ? false : { opacity: 0, y: 10, scale: 0.98 }}
        animate={{ opacity: 1, y: 0, scale: overlay ? 1.02 : 1 }}
        transition={{ duration: 0.32, ease: [0.2, 0.8, 0.2, 1], delay: Math.min(index, 10) * 0.045 }}
        aria-label={title}
        className={cn(
          '@container group/w flex h-full flex-col rounded-card border bg-surface transition-[box-shadow,border-color] duration-200',
          overlay ? 'border-border-strong shadow-lift' : 'border-border shadow-card hover:shadow-lift/40',
          editing && !overlay && 'border-dashed border-border-strong',
        )}
      >
        <header className="flex items-center gap-2 px-pad pt-pad pb-3">
          {editing && (
            <button
              {...handleProps}
              className="-ml-2 flex size-8 shrink-0 cursor-grab touch-none items-center justify-center rounded-control text-subtle hover:bg-surface-2 hover:text-fg active:cursor-grabbing"
              aria-label={`Move ${title}`}
            >
              <GripVertical className="size-4" />
            </button>
          )}
          <meta.icon className="size-4 shrink-0 text-muted" />
          <EditableTitle widget={widget} dashboardId={dashboardId} editing={editing} title={title} />
          {meta.sample && env.sample && <Badge className="ml-1">Sample</Badge>}
          {editing && <WidgetMenu widget={widget} dashboardId={dashboardId} title={title} />}
        </header>
        <div className="flex-1 px-pad pb-pad">
          <Body widget={widget} dashboardId={dashboardId} editing={editing} />
        </div>
      </motion.section>
    </div>
  )
})

function EditableTitle({ widget, dashboardId, editing, title }: { widget: Widget; dashboardId: string; editing: boolean; title: string }) {
  const { dispatch } = usePreferences()
  const [draft, setDraft] = useState<string | null>(null)
  if (draft !== null) {
    const commit = () => {
      dispatch({ type: 'updateWidget', dashboard: dashboardId, id: widget.id, patch: { title: draft.trim() === WIDGETS[widget.type].name ? '' : draft } })
      setDraft(null)
    }
    return (
      <input
        autoFocus
        value={draft}
        maxLength={60}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === 'Enter') commit()
          if (e.key === 'Escape') setDraft(null)
        }}
        aria-label="Widget title"
        className="min-w-0 flex-1 rounded-sm bg-surface-2 px-1.5 py-0.5 text-sm font-semibold outline-none ring-2 ring-ring"
      />
    )
  }
  return (
    <h2
      className={cn('min-w-0 flex-1 truncate text-sm font-semibold', editing && 'cursor-text rounded-sm hover:bg-surface-2')}
      onDoubleClick={editing ? () => setDraft(title) : undefined}
      title={editing ? 'Double-click to rename' : undefined}
    >
      {title}
    </h2>
  )
}

function WidgetMenu({ widget, dashboardId, title }: { widget: Widget; dashboardId: string; title: string }) {
  const { dispatch } = usePreferences()
  const undoable = useUndoable()
  const [renaming, setRenaming] = useState(false)
  return (
    <>
      <Segmented
        label={`Size of ${title}`}
        value={widget.size}
        onChange={(size) => dispatch({ type: 'updateWidget', dashboard: dashboardId, id: widget.id, patch: { size } })}
        options={WIDGET_SIZES.map((s) => ({ value: s, label: s.toUpperCase(), title: SIZE_LABEL[s] }))}
        className="hidden @sm:inline-flex [&_button]:h-6 [&_button]:min-w-7 [&_button]:px-1.5 [&_button]:text-[0.6875rem]"
      />
      <Menu>
        <MenuTrigger asChild>
          <Button variant="ghost" size="icon-sm" aria-label={`Options for ${title}`}><MoreHorizontal /></Button>
        </MenuTrigger>
        <MenuContent>
          <MenuLabel>Size</MenuLabel>
          {WIDGET_SIZES.map((s) => (
            <MenuItem key={s} onSelect={() => dispatch({ type: 'updateWidget', dashboard: dashboardId, id: widget.id, patch: { size: s } })}>
              <span className={cn('size-1.5 rounded-full', widget.size === s ? 'bg-accent' : 'bg-transparent')} />{SIZE_LABEL[s]}
            </MenuItem>
          ))}
          <MenuSeparator />
          <MenuItem icon={<PencilLine />} onSelect={() => setRenaming(true)}>Rename</MenuItem>
          <MenuItem icon={<Trash2 />} destructive onSelect={() => undoable({ type: 'removeWidget', dashboard: dashboardId, id: widget.id }, `Removed “${title}”`)}>Remove</MenuItem>
        </MenuContent>
      </Menu>
      {renaming && <RenameDialog widget={widget} dashboardId={dashboardId} title={title} onDone={() => setRenaming(false)} />}
    </>
  )
}

function RenameDialog({ widget, dashboardId, title, onDone }: { widget: Widget; dashboardId: string; title: string; onDone: () => void }) {
  const { dispatch } = usePreferences()
  const [value, setValue] = useState(title)
  const defaultName = WIDGETS[widget.type].name
  return (
    <Dialog open onOpenChange={(open) => !open && onDone()} title="Rename widget" description={`Leave it as “${defaultName}” to use the default name.`}>
      <form
        className="flex flex-col gap-4 p-pad"
        onSubmit={(e) => {
          e.preventDefault()
          dispatch({ type: 'updateWidget', dashboard: dashboardId, id: widget.id, patch: { title: value.trim() === defaultName ? '' : value } })
          onDone()
        }}
      >
        <Input autoFocus maxLength={60} value={value} onChange={(e) => setValue(e.target.value)} aria-label="Widget title" />
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onDone}>Cancel</Button>
          <Button variant="primary" type="submit">Save</Button>
        </div>
      </form>
    </Dialog>
  )
}
