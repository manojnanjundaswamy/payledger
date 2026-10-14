import { useNavigate } from '@tanstack/react-router'
import { ArrowDownLeft, ArrowLeftRight, ArrowUpRight, Plus, Upload, Users } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Menu, MenuContent, MenuItem, MenuLabel, MenuSeparator, MenuTrigger } from '@/components/ui/overlay'

function soon(what: string, phase: string) {
  toast(`${what} is coming in ${phase}`, { description: 'Your accounts and budgets will start filling these dashboards.' })
}

/** The one button that adds money things. Splitting and import arrive later (Phase 5 and the import piece of Phase 4). */
export function AddMenu({ compact, trigger }: { compact?: boolean; trigger?: React.ReactNode }) {
  const navigate = useNavigate()
  const add = (kind: 'EXPENSE' | 'INCOME' | 'TRANSFER') => void navigate({ to: '/transactions', search: { add: kind } })
  return (
    <Menu>
      <MenuTrigger asChild>
        {trigger ?? (
          <Button variant="primary" size={compact ? 'icon' : 'md'} className={compact ? '' : 'w-full'} aria-label="Add">
            <Plus />
            {!compact && 'Add'}
          </Button>
        )}
      </MenuTrigger>
      <MenuContent align="start" returnFocus={false}>
        <MenuLabel>Record</MenuLabel>
        <MenuItem icon={<ArrowUpRight />} onSelect={() => add('EXPENSE')}>Expense</MenuItem>
        <MenuItem icon={<ArrowDownLeft />} onSelect={() => add('INCOME')}>Income</MenuItem>
        <MenuItem icon={<ArrowLeftRight />} onSelect={() => add('TRANSFER')}>Transfer between accounts</MenuItem>
        <MenuSeparator />
        <MenuItem icon={<Users />} onSelect={() => soon('Splitting', 'Phase 5')}>Split a bill</MenuItem>
        <MenuItem icon={<Upload />} onSelect={() => soon('Importing', 'Phase 4')}>Import from a file</MenuItem>
      </MenuContent>
    </Menu>
  )
}
