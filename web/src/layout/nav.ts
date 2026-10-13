import {
  ArrowLeftRight, BarChart3, Calculator, CalendarClock, Home, LayoutDashboard, PieChart, Settings, ShieldCheck, Tags, UserRoundPlus, Users, Wallet, type LucideIcon,
} from 'lucide-react'
import { useProfile } from '@/state/profile'

export interface NavItem {
  to: string
  label: string
  icon: LucideIcon
  section: 'money' | 'together' | 'app'
  /** shown in the phone tab bar */
  primary?: boolean
  /** only for PayLedger staff (the admin console) */
  staff?: boolean
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
  { to: '/utilities', label: 'Utilities', icon: Calculator, section: 'app' },
  { to: '/admin', label: 'Admin', icon: ShieldCheck, section: 'app', staff: true },
  { to: '/settings', label: 'Settings', icon: Settings, section: 'app' },
]

/** The navigation this user sees: the admin console only for staff. */
export function useNav(): NavItem[] {
  const staff = !!useProfile().data?.staff
  return staff ? NAV : NAV.filter((n) => !n.staff)
}
