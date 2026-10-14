import { useNavigate } from '@tanstack/react-router'
import { ArrowDownLeft, ArrowLeftRight, ArrowUpRight, Goal, Plus, Upload, Users } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Menu, MenuContent, MenuItem, MenuLabel, MenuSeparator, MenuTrigger } from '@/components/ui/overlay'

/** The one button that adds money things: an entry, money for a goal, a shared expense, or a file. */
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
        <MenuItem icon={<Goal />} onSelect={() => void navigate({ to: '/goals' })}>Save for a goal</MenuItem>
        <MenuSeparator />
        <MenuItem icon={<Users />} onSelect={() => void navigate({ to: '/groups' })}>Split a bill</MenuItem>
        <MenuItem icon={<Upload />} onSelect={() => void navigate({ to: '/import' })}>Import from a file</MenuItem>
      </MenuContent>
    </Menu>
  )
}
