import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { useProfile, type StaffPermission } from '@/state/profile'

export interface Overview {
  today: string
  zone: string
  users: { total: number; newToday: number; new7Days: number; new30Days: number }
  active: { daily: number; weekly: number; monthly: number }
  plans: { free: number; trial: number; paid: number; granted: number; complimentary: number; trialsStarted30Days: number; trialsEndingSoon: number; convertedFromTrial: number }
  days: { day: string; signups: number; active: number }[]
  adoption: { users: number; accounts: number; transactions: number; budgets: number; bills: number; imports: number; groups: number }
  usage: { transactions: number; groupExpenses: number; imports: number; groups: number }
  /** Weekly sign-up cohorts, oldest first; retained[k] = share active k weeks after signing up. */
  cohorts: { week: string; size: number; retained: number[] }[]
}

export function useOverview(days: number) {
  return useQuery({
    queryKey: ['admin', 'overview', days],
    queryFn: () => api<Overview>(`/api/core/admin/overview?days=${days}`),
    placeholderData: keepPreviousData,
    refetchInterval: 60_000,
  })
}

/** Whether the signed-in staff member's role allows this. The server checks again on every call. */
export function useCan(permission: StaffPermission): boolean {
  return useProfile().data?.staff?.permissions.includes(permission) ?? false
}

export type PlanKind = 'FREE' | 'TRIAL' | 'PAID' | 'GRANTED' | 'COMPLIMENTARY'
export type StaffRoleName = 'OWNER' | 'ADMIN' | 'SUPPORT' | 'ANALYST'

export interface AdminUser {
  id: string
  email: string
  displayName: string
  createdAt: string
  lastActiveOn: string | null
  plan: PlanKind
  premiumUntil: string | null
  disabled: boolean
  staffRole: StaffRoleName | null
}

export interface AuditEntry {
  id: string
  at: string
  action: string
  actorId: string
  actorEmail: string
  targetId: string | null
  targetEmail: string | null
  details: Record<string, unknown>
}

export interface AdminUserDetail {
  user: AdminUser
  subject: string
  locale: string
  timeZone: string
  currency: string
  trialStartedAt: string | null
  disabledAt: string | null
  usage: { accounts: number; transactions: number; budgets: number; bills: number; imports: number; groups: number; activeDays30: number }
  history: AuditEntry[]
}

export interface UserFilter { q: string; plan?: string; status?: string }

export function useAdminUsers(filter: UserFilter, limit: number) {
  const params = new URLSearchParams({ limit: String(limit) })
  if (filter.q.trim()) params.set('q', filter.q.trim())
  if (filter.plan) params.set('plan', filter.plan)
  if (filter.status) params.set('status', filter.status)
  return useQuery({
    queryKey: ['admin', 'users', filter, limit],
    queryFn: () => api<{ users: AdminUser[]; total: number }>(`/api/core/admin/users?${params}`),
    placeholderData: keepPreviousData,
  })
}

export function useAdminUser(id: string | null) {
  return useQuery({
    queryKey: ['admin', 'user', id],
    queryFn: () => api<AdminUserDetail>(`/api/core/admin/users/${id}`),
    enabled: !!id,
  })
}

/** A change to one user: the answer is their new details; lists and the dashboard refresh. */
export function useAdminUserAction<T>(fn: (input: T) => Promise<AdminUserDetail | void>) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: fn,
    onSuccess: (detail) => {
      if (detail) client.setQueryData(['admin', 'user', detail.user.id], detail)
      void client.invalidateQueries({ queryKey: ['admin'] })
    },
  })
}

export const adminApi = {
  create: (body: { email: string; displayName: string; premiumUntil?: string }) =>
    api<AdminUserDetail>('/api/core/admin/users', { method: 'POST', json: body }),
  setDisabled: (id: string, disabled: boolean) =>
    api<AdminUserDetail>(`/api/core/admin/users/${id}/disabled`, { method: 'PUT', json: { disabled } }),
  grant: (id: string, until: string) => api<AdminUserDetail>(`/api/core/admin/users/${id}/premium`, { method: 'PUT', json: { until } }),
  end: (id: string) => api<AdminUserDetail>(`/api/core/admin/users/${id}/premium`, { method: 'DELETE' }),
  email: (id: string, kind: 'PASSWORD_RESET' | 'VERIFY_EMAIL') =>
    api<void>(`/api/core/admin/users/${id}/emails`, { method: 'POST', json: { kind } }),
  signOut: (id: string) => api<void>(`/api/core/admin/users/${id}/sign-out`, { method: 'POST' }),
}

export function useAudit(limit: number) {
  return useQuery({
    queryKey: ['admin', 'audit', limit],
    queryFn: () => api<AuditEntry[]>(`/api/core/admin/audit?limit=${limit}`),
    placeholderData: keepPreviousData,
  })
}

export const PLAN_LABEL: Record<PlanKind, string> = {
  FREE: 'Free', TRIAL: 'Trial', PAID: 'Paying', GRANTED: 'Given by us', COMPLIMENTARY: 'Complimentary',
}

/** Audit actions in words. */
export const ACTION_LABEL: Record<string, string> = {
  'user.create': 'created the account',
  'user.disable': 'disabled the account',
  'user.enable': 'enabled the account',
  'user.email.password-reset': 'sent a password reset email',
  'user.email.verify': 'sent a verification email',
  'user.sign-out': 'signed them out everywhere',
  'plan.grant': 'gave Premium',
  'plan.end': 'ended Premium',
  'staff.grant': 'gave a staff role',
  'staff.remove': 'removed a staff role',
  'settings.update': 'changed the settings',
}

export interface StaffMember {
  userId: string
  email: string
  displayName: string
  role: StaffRoleName
  /** An owner from ADMIN_EMAILS: can't be changed in the console. */
  configured: boolean
  grantedAt: string | null
  grantedBy: string | null
}

export function useStaff() {
  return useQuery({ queryKey: ['admin', 'staff'], queryFn: () => api<StaffMember[]>('/api/core/admin/staff') })
}

export function useStaffChange() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (change: { grant: { email: string; role: StaffRoleName } } | { remove: string }): Promise<void> => {
      if ('grant' in change) await api<StaffMember[]>('/api/core/admin/staff', { method: 'PUT', json: change.grant })
      else await api<void>(`/api/core/admin/staff/${change.remove}`, { method: 'DELETE' })
    },
    onSuccess: () => client.invalidateQueries({ queryKey: ['admin'] }),
  })
}

export interface Announcement { message: string; tone: 'INFO' | 'WARNING'; until: string | null }

export interface AppSettings {
  freeLimits: { accounts: number; budgets: number; bills: number; bigCosts: number; imports: number }
  trialDays: number
  announcement: Announcement | null
}

export function useAppSettings() {
  return useQuery({ queryKey: ['admin', 'settings'], queryFn: () => api<AppSettings>('/api/core/admin/settings') })
}

export function useSaveAppSettings() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (s: AppSettings) => api<AppSettings>('/api/core/admin/settings', { method: 'PUT', json: s }),
    onSuccess: (s) => {
      client.setQueryData(['admin', 'settings'], s)
      void client.invalidateQueries({ queryKey: ['announcement'] })
      void client.invalidateQueries({ queryKey: ['plan'] })
      void client.invalidateQueries({ queryKey: ['admin', 'audit'] })
    },
  })
}

/** The banner everyone signed in sees; null when there is none. */
export function useAnnouncement() {
  return useQuery({
    queryKey: ['announcement'],
    queryFn: async () => (await api<Announcement | undefined>('/api/core/announcement')) ?? null,
    staleTime: 5 * 60_000,
  })
}
