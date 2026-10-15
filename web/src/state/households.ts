import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'

export type HouseholdRole = 'OWNER' | 'PARTNER' | 'MEMBER' | 'VIEWER'

export interface HouseholdSummary { id: string; name: string; currency: string; role: HouseholdRole; members: number }
export interface HouseholdMember { userId: string; name: string; email: string; role: HouseholdRole; joinedAt: string; you: boolean }
export interface HouseholdInvite { id: string; email: string; role: HouseholdRole; createdAt: string; expiresAt: string }
export interface Contribution { userId: string; name: string; spent: number; entries: number; share: number }
export interface Household {
  id: string; name: string; currency: string; role: HouseholdRole
  members: HouseholdMember[]; invites: HouseholdInvite[]
  /** yyyy-mm, and the month's household spending, by who recorded it */
  month: string; spent: number; contributions: Contribution[]
}
export interface InviteInfo { household: string; invitedBy: string; email: string; role: HouseholdRole; forYou: boolean; alreadyMember: boolean; expired: boolean }

export const ROLES: Record<HouseholdRole, { label: string; hint: string }> = {
  OWNER: { label: 'Owner', hint: 'Manages everything; one per household' },
  PARTNER: { label: 'Partner', hint: 'Adds and changes money, invites people' },
  MEMBER: { label: 'Member', hint: 'Adds and changes money' },
  VIEWER: { label: 'Viewer', hint: 'Sees everything, changes nothing (a teenager, say)' },
}

export function useHouseholds() {
  return useQuery({ queryKey: ['households'], queryFn: () => api<HouseholdSummary[]>('/api/core/households') })
}

export function useHousehold(id: string | undefined, month?: string) {
  return useQuery({
    queryKey: ['households', id, month ?? 'now'],
    queryFn: () => api<Household>(`/api/core/households/${id}${month ? `?month=${month}` : ''}`),
    enabled: !!id,
  })
}

function useHouseholdWrite<V, R>(write: (v: V) => Promise<R>) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: write,
    onSettled: () => {
      void client.invalidateQueries({ queryKey: ['households'] })
      // Your spaces (the switcher) come with the profile.
      void client.invalidateQueries({ queryKey: ['profile'] })
    },
  })
}

export const useCreateHousehold = () => useHouseholdWrite((body: { name: string }) => api<Household>('/api/core/households', { method: 'POST', json: body }))
export const useRenameHousehold = (id: string) => useHouseholdWrite((name: string) => api<Household>(`/api/core/households/${id}`, { method: 'PATCH', json: { name } }))
export const useInvite = (id: string) => useHouseholdWrite((body: { email: string; role: HouseholdRole }) =>
  api<Household>(`/api/core/households/${id}/invites`, { method: 'POST', json: body }))
export const useRevokeInvite = (id: string) => useHouseholdWrite((inviteId: string) => api<Household>(`/api/core/households/${id}/invites/${inviteId}`, { method: 'DELETE' }))
export const useSetRole = (id: string) => useHouseholdWrite(({ userId, role }: { userId: string; role: HouseholdRole }) =>
  api<Household>(`/api/core/households/${id}/members/${userId}`, { method: 'PATCH', json: { role } }))
export const useRemoveMember = (id: string) => useHouseholdWrite((userId: string) => api<void>(`/api/core/households/${id}/members/${userId}`, { method: 'DELETE' }))
export const useJoinHousehold = () => useHouseholdWrite((token: string) => api<Household>('/api/core/households/join', { method: 'POST', json: { token } }))

export function useInviteInfo(token: string | undefined) {
  return useQuery({ queryKey: ['household-invite', token], queryFn: () => api<InviteInfo>(`/api/core/households/invite?token=${encodeURIComponent(token!)}`), enabled: !!token, retry: false })
}
