import { queryOptions, useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'

export interface Space {
  id: string
  kind: 'PERSONAL' | 'HOUSEHOLD'
  name: string
  currency: string
  role: 'OWNER' | 'PARTNER' | 'MEMBER' | 'VIEWER'
}

export type StaffRole = 'OWNER' | 'ADMIN' | 'SUPPORT' | 'ANALYST'
export type StaffPermission = 'ANALYTICS_VIEW' | 'USERS_VIEW' | 'USERS_SUPPORT' | 'USERS_MANAGE' | 'PLANS_MANAGE'
  | 'SETTINGS_MANAGE' | 'AUDIT_VIEW' | 'STAFF_MANAGE'

export interface Profile {
  id: string
  email: string
  displayName: string
  /** country: null until the region is set up (the app asks at sign-in); fyStartMonth: 4 = April–March, 1 = calendar year */
  settings: { locale: string; timeZone: string; currency: string; budgetStartDay: number; upiId?: string | null; country?: string | null; fyStartMonth?: number }
  preferences: Record<string, unknown>
  spaces: Space[]
  /** PayLedger staff: their admin-console role and what it allows. Absent for customers. */
  staff?: { role: StaffRole; permissions: StaffPermission[] } | null
}

export type ProfileUpdate = Partial<Pick<Profile, 'displayName'> & Omit<Profile['settings'], 'upiId'>> & { preferences?: unknown; upiId?: string }

export const profileQuery = queryOptions({
  queryKey: ['profile'],
  queryFn: () => api<Profile>('/api/core/me'),
  staleTime: 5 * 60_000,
})

export function useProfile() {
  return useQuery(profileQuery)
}

export function updateProfile(update: ProfileUpdate) {
  return api<Profile>('/api/core/me', { method: 'PATCH', json: update })
}

export function useUpdateProfile() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: updateProfile,
    onSuccess: (profile) => client.setQueryData(profileQuery.queryKey, profile),
  })
}
