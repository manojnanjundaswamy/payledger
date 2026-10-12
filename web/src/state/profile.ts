import { queryOptions, useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'

export interface Space {
  id: string
  kind: 'PERSONAL' | 'HOUSEHOLD'
  name: string
  currency: string
  role: 'OWNER' | 'PARTNER' | 'MEMBER' | 'VIEWER'
}

export interface Profile {
  id: string
  email: string
  displayName: string
  settings: { locale: string; timeZone: string; currency: string; budgetStartDay: number }
  preferences: Record<string, unknown>
  spaces: Space[]
}

export type ProfileUpdate = Partial<Pick<Profile, 'displayName'> & Profile['settings']> & { preferences?: unknown }

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
