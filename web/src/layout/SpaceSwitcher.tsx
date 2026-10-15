import { useNavigate } from '@tanstack/react-router'
import { useQueryClient } from '@tanstack/react-query'
import { Check, ChevronsUpDown, Home, Plus, User } from 'lucide-react'
import { toast } from 'sonner'
import { Menu, MenuContent, MenuItem, MenuLabel, MenuSeparator, MenuTrigger } from '@/components/ui/overlay'
import { cn } from '@/lib/cn'
import { useSpaceId } from '@/state/money'
import { useProfile } from '@/state/profile'
import { chooseSpace } from '@/state/space'

/**
 * Personal ↔ household, next to the page title. Every money page follows the choice, so the household gets
 * accounts, budgets, the plan and reports without pages of its own. Shown once you're in a household.
 */
export function SpaceSwitcher() {
  const spaces = useProfile().data?.spaces ?? []
  const current = useSpaceId()
  const navigate = useNavigate()
  const client = useQueryClient()
  const households = spaces.filter((s) => s.kind === 'HOUSEHOLD')
  if (households.length === 0) return null
  const here = spaces.find((s) => s.id === current)
  const inHousehold = here?.kind === 'HOUSEHOLD'

  function choose(id: string, name: string, household: boolean) {
    if (id === current) return
    chooseSpace(household ? id : null)
    // Lists and charts belong to a space: start them fresh, so nothing from the other space flashes up.
    void client.invalidateQueries({ queryKey: ['money'] })
    toast(household ? `Now in ${name}` : 'Back to your own money', { description: household ? 'Accounts, budgets, the plan and reports are the household\'s.' : undefined })
  }

  return (
    <Menu>
      <MenuTrigger asChild>
        <button type="button" aria-label={`Space: ${here?.name ?? 'Personal'}. Switch`} data-testid="space-switcher"
          className={cn('flex h-8 max-w-48 items-center gap-1.5 rounded-full border px-2.5 text-xs font-medium transition-colors',
            inHousehold ? 'border-transparent bg-accent text-accent-fg hover:opacity-90' : 'border-border bg-surface-2 text-muted hover:text-fg')}>
          {inHousehold ? <Home className="size-3.5 shrink-0" /> : <User className="size-3.5 shrink-0" />}
          <span className="truncate">{inHousehold ? here!.name : 'Personal'}</span>
          <ChevronsUpDown className="size-3 shrink-0 opacity-70" />
        </button>
      </MenuTrigger>
      <MenuContent align="start">
        <MenuLabel>Whose money</MenuLabel>
        {spaces.map((s) => (
          <MenuItem key={s.id} icon={s.kind === 'HOUSEHOLD' ? <Home /> : <User />}
            onSelect={() => choose(s.id, s.name, s.kind === 'HOUSEHOLD')}>
            <span className="flex flex-1 items-center justify-between gap-3">
              {s.kind === 'PERSONAL' ? 'Personal (only you)' : s.name}
              {s.id === current && <Check className="size-4 text-accent" />}
            </span>
          </MenuItem>
        ))}
        <MenuSeparator />
        <MenuItem icon={<Plus />} onSelect={() => void navigate({ to: '/household' })}>Households…</MenuItem>
      </MenuContent>
    </Menu>
  )
}
