import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { useProfile, type StaffPermission } from '@/state/profile'

export interface Overview {
  today: string
  zone: string
  users: { total: number; newToday: number; new7Days: number; new30Days: number }
  active: { daily: number; weekly: number; monthly: number }
  plans: { free: number; trial: number; paid: number; complimentary: number; trialsStarted30Days: number; trialsEndingSoon: number; convertedFromTrial: number }
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
