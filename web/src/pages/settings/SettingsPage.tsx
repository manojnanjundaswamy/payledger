import { useNavigate, useSearch } from '@tanstack/react-router'
import { motion } from 'motion/react'
import { Globe2, LayoutGrid, Palette, ShieldCheck, Sparkles, UserRound } from 'lucide-react'
import { cn } from '@/lib/cn'
import { AppearanceSettings } from './AppearanceSettings'
import { AccountSettings, DashboardSettings, ProfileSettings, RegionalSettings } from './OtherSettings'
import { PlanSettings } from './PlanSettings'

export const SETTINGS_TABS = [
  { key: 'appearance', label: 'Appearance', icon: Palette, component: AppearanceSettings },
  { key: 'profile', label: 'Profile', icon: UserRound, component: ProfileSettings },
  { key: 'regional', label: 'Region & money', icon: Globe2, component: RegionalSettings },
  { key: 'dashboards', label: 'Dashboards', icon: LayoutGrid, component: DashboardSettings },
  { key: 'plan', label: 'Plan', icon: Sparkles, component: PlanSettings },
  { key: 'account', label: 'Account', icon: ShieldCheck, component: AccountSettings },
] as const

export function SettingsPage() {
  const { tab } = useSearch({ from: '/app/settings' })
  const navigate = useNavigate()
  const current = SETTINGS_TABS.find((t) => t.key === tab) ?? SETTINGS_TABS[0]
  const Body = current.component
  return (
    <div className="flex flex-col gap-6 lg:flex-row lg:gap-10">
      <nav className="-mx-4 flex gap-1 overflow-x-auto px-4 lg:mx-0 lg:w-52 lg:shrink-0 lg:flex-col lg:px-0" aria-label="Settings">
        {SETTINGS_TABS.map((t) => (
          <button key={t.key} onClick={() => navigate({ to: '/settings', search: { tab: t.key }, replace: true })}
            aria-current={t.key === current.key ? 'page' : undefined}
            className={cn('relative flex h-10 shrink-0 items-center gap-2.5 rounded-control px-3 text-sm font-medium transition-colors',
              t.key === current.key ? 'text-fg' : 'text-muted hover:bg-surface-2 hover:text-fg')}>
            {t.key === current.key && <motion.span layoutId="settings-tab" className="absolute inset-0 rounded-control bg-surface-2" />}
            <t.icon className="relative size-4" /><span className="relative">{t.label}</span>
          </button>
        ))}
      </nav>
      <motion.div key={current.key} initial={{ opacity: 0, x: 6 }} animate={{ opacity: 1, x: 0 }} className="min-w-0 flex-1">
        <Body />
      </motion.div>
    </div>
  )
}
