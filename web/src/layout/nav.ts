import {
  ArrowLeftRight, BarChart3, CalendarClock, Home, LayoutDashboard, PieChart, Settings, Tags, UserRoundPlus, Users, Wallet, type LucideIcon,
} from 'lucide-react'

export interface NavItem {
  to: string
  label: string
  icon: LucideIcon
  section: 'money' | 'together' | 'app'
  /** shown in the phone tab bar */
  primary?: boolean
}

export const NAV: NavItem[] = [
  { to: '/dashboard', label: 'Dashboard', icon: LayoutDashboard, section: 'money', primary: true },
  { to: '/transactions', label: 'Transactions', icon: ArrowLeftRight, section: 'money', primary: true },
  { to: '/budgets', label: 'Budgets', icon: PieChart, section: 'money', primary: true },
  { to: '/accounts', label: 'Accounts', icon: Wallet, section: 'money' },
  { to: '/categories', label: 'Categories', icon: Tags, section: 'money' },
  { to: '/plan', label: 'Plan', icon: CalendarClock, section: 'money' },
  { to: '/reports', label: 'Reports', icon: BarChart3, section: 'money' },
  { to: '/household', label: 'Household', icon: Home, section: 'together' },
  { to: '/groups', label: 'Groups', icon: Users, section: 'together' },
  { to: '/people', label: 'People', icon: UserRoundPlus, section: 'together' },
  { to: '/settings', label: 'Settings', icon: Settings, section: 'app' },
]
