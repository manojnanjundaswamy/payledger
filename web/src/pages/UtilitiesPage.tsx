import { useNavigate, useSearch } from '@tanstack/react-router'
import { ExternalLink } from 'lucide-react'
import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Switch } from '@/components/ui/controls'
import { cn } from '@/lib/cn'
import { DOCK_HIDDEN } from '@/utilities/EdgeDock'
import { GROUPS, TOOL_IDS, TOOLS, type ToolId } from '@/utilities/registry'
import { useUtilityWindows } from '@/utilities/windows'

/** Every utility, by section; the one chosen works right here, or pops out into its own window. */
export function UtilitiesPage() {
  const { tool } = useSearch({ from: '/app/utilities' })
  const navigate = useNavigate({ from: '/utilities' })
  const windows = useUtilityWindows()
  const current = (tool ?? 'calculator') as ToolId
  const T = TOOLS[current]
  const [dockHidden, setDockHidden] = useState(() => { try { return localStorage.getItem(DOCK_HIDDEN) === '1' } catch { return false } })

  function toggleDock(show: boolean) {
    try { localStorage.setItem(DOCK_HIDDEN, show ? '0' : '1') } catch { /* stays as it is */ }
    setDockHidden(!show)
    window.dispatchEvent(new Event('edge-dock-change'))
  }

  return (
    <div className="flex flex-col gap-6 lg:flex-row lg:gap-8">
      <nav aria-label="Utilities" className="flex flex-col gap-4 lg:w-60 lg:shrink-0">
        {GROUPS.map((g) => (
          <div key={g} className="flex flex-col gap-0.5">
            <h2 className="px-3 pb-1 text-[0.6875rem] font-medium tracking-wider text-subtle uppercase">{g}</h2>
            {TOOL_IDS.filter((id) => TOOLS[id].group === g).map((id) => {
              const t = TOOLS[id]
              return (
                <button key={id} type="button" onClick={() => void navigate({ search: { tool: id }, replace: true })} aria-current={id === current ? 'page' : undefined}
                  className={cn('flex h-9 items-center gap-2.5 rounded-control px-3 text-left text-sm font-medium', id === current ? 'bg-surface-2 text-fg' : 'text-muted hover:bg-surface-2 hover:text-fg')}>
                  <t.icon className="size-4" />{t.name}
                </button>
              )
            })}
          </div>
        ))}
        <label className="flex items-center gap-2 px-3 text-xs text-muted">
          <Switch label="Edge panel" checked={!dockHidden} onCheckedChange={toggleDock} />Edge panel on every page
        </label>
      </nav>
      <Card className="flex min-w-0 max-w-3xl flex-1 flex-col gap-4 p-pad">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 className="flex items-center gap-2 text-lg font-semibold"><T.icon className="size-5" />{T.name}</h2>
            <p className="text-sm text-muted">{T.does}</p>
          </div>
          <Button variant="outline" size="sm" onClick={() => windows.open(current)}><ExternalLink />Pop out</Button>
        </div>
        <T.component />
      </Card>
    </div>
  )
}
