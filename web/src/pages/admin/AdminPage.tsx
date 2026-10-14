import { useNavigate, useSearch } from '@tanstack/react-router'
import { Activity, BarChart3, Mail, Network, ScrollText, Settings2, ShieldAlert, ShieldCheck, Users } from 'lucide-react'
import { Badge, Card } from '@/components/ui/card'
import { cn } from '@/lib/cn'
import { useProfile, type StaffPermission } from '@/state/profile'
import { AdminActivity } from './AdminActivity'
import { AdminAudit } from './AdminAudit'
import { AdminRequests } from './AdminRequests'
import { AdminEmails } from './AdminEmails'
import { AdminOverview } from './AdminOverview'
import { AdminSettings } from './AdminSettings'
import { AdminStaff } from './AdminStaff'
import { AdminUsers } from './AdminUsers'
import { ADMIN_TABS_KEYS, type AdminTab } from './tabs'
import { setAdminTimeZone } from './parts'

const TABS: Record<AdminTab, { label: string; icon: typeof BarChart3; needs: StaffPermission; component: () => React.JSX.Element }> = {
  overview: { label: 'Overview', icon: BarChart3, needs: 'ANALYTICS_VIEW', component: AdminOverview },
  users: { label: 'Users', icon: Users, needs: 'USERS_VIEW', component: AdminUsers },
  staff: { label: 'Staff', icon: ShieldCheck, needs: 'STAFF_MANAGE', component: AdminStaff },
  settings: { label: 'Settings', icon: Settings2, needs: 'SETTINGS_MANAGE', component: AdminSettings },
  emails: { label: 'Emails', icon: Mail, needs: 'USERS_SUPPORT', component: AdminEmails },
  activity: { label: 'User activity', icon: Activity, needs: 'USERS_VIEW', component: AdminActivity },
  requests: { label: 'Requests', icon: Network, needs: 'AUDIT_VIEW', component: AdminRequests },
  audit: { label: 'Audit log', icon: ScrollText, needs: 'AUDIT_VIEW', component: AdminAudit },
}

const ROLE_LABEL = { OWNER: 'Owner', ADMIN: 'Admin', SUPPORT: 'Support', ANALYST: 'Analyst' } as const

/** The admin console: only staff get here, and each tab only shows when the role allows it. */
export function AdminPage() {
  const profile = useProfile().data
  const staff = profile?.staff
  setAdminTimeZone(profile?.settings.timeZone)
  const { tab } = useSearch({ from: '/app/admin' })
  const navigate = useNavigate()

  if (!staff) {
    return (
      <Card className="mx-auto flex max-w-md flex-col items-center gap-2 p-8 text-center">
        <ShieldAlert className="size-8 text-muted" />
        <p className="font-medium">This is for PayLedger staff</p>
        <p className="text-sm text-muted">Your account doesn't have access to the admin console.</p>
      </Card>
    )
  }
  const allowed = ADMIN_TABS_KEYS.filter((k) => staff.permissions.includes(TABS[k].needs))
  const current = allowed.find((k) => k === tab) ?? allowed[0]
  const Body = current ? TABS[current].component : null

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-center gap-3">
        <nav className="flex gap-1 overflow-x-auto" aria-label="Admin">
          {allowed.map((k) => {
            const t = TABS[k]
            return (
              <button key={k} onClick={() => navigate({ to: '/admin', search: { tab: k }, replace: true })}
                aria-current={k === current ? 'page' : undefined}
                className={cn('flex h-9 shrink-0 items-center gap-2 rounded-control px-3 text-sm font-medium transition-colors',
                  k === current ? 'bg-surface-2 text-fg' : 'text-muted hover:bg-surface-2 hover:text-fg')}>
                <t.icon className="size-4" />{t.label}
              </button>
            )
          })}
        </nav>
        <span className="ml-auto text-xs text-subtle">Times in {profile?.settings.timeZone}</span>
        <Badge data-testid="staff-role">{ROLE_LABEL[staff.role]}</Badge>
      </div>
      {Body ? <Body /> : <Card className="p-pad text-sm text-muted">Your role has nothing to show here yet.</Card>}
    </div>
  )
}
