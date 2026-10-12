import { ArrowDownLeft, ArrowLeftRight, ArrowUpRight, Plus, Upload, Users } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Menu, MenuContent, MenuItem, MenuLabel, MenuSeparator, MenuTrigger } from '@/components/ui/overlay'

function soon(what: string, phase: string) {
  toast(`${what} is coming in ${phase}`, { description: 'Your accounts and budgets will start filling these dashboards.' })
}

/** The one button that adds money things. Real forms arrive with transactions (Phase 4) and splitting (Phase 5). */
export function AddMenu({ compact, trigger }: { compact?: boolean; trigger?: React.ReactNode }) {
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
      <MenuContent align="start">
        <MenuLabel>Record</MenuLabel>
        <MenuItem icon={<ArrowUpRight />} onSelect={() => soon('Adding expenses', 'Phase 4')}>Expense</MenuItem>
        <MenuItem icon={<ArrowDownLeft />} onSelect={() => soon('Adding income', 'Phase 4')}>Income</MenuItem>
        <MenuItem icon={<ArrowLeftRight />} onSelect={() => soon('Transfers', 'Phase 4')}>Transfer between accounts</MenuItem>
        <MenuSeparator />
        <MenuItem icon={<Users />} onSelect={() => soon('Splitting', 'Phase 5')}>Split a bill</MenuItem>
        <MenuItem icon={<Upload />} onSelect={() => soon('Importing', 'Phase 4')}>Import from a file</MenuItem>
      </MenuContent>
    </Menu>
  )
}
