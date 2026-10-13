import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'

/** core-service's groups API (docs/adr/0007-groups-and-splitting.md). */

export interface GroupSummary { id: string; name: string; emoji: string | null; currency: string; role: 'OWNER' | 'MEMBER'; members: number; archived: boolean }

/** A person in a group: joined (has an account) or a placeholder, maybe invited by email. */
export interface GroupMember { id: string; name: string; email: string | null; role: 'OWNER' | 'MEMBER'; joined: boolean; you: boolean }

export interface Group { id: string; name: string; emoji: string | null; currency: string; archived: boolean; you: string; members: GroupMember[] }

export interface GroupInvite { groupName: string; emoji: string | null; members: number; alreadyMember: boolean }

export function useGroups() {
  return useQuery({ queryKey: ['groups'], queryFn: () => api<GroupSummary[]>('/api/core/groups') })
}

export function useGroup(id: string) {
  return useQuery({ queryKey: ['groups', id], queryFn: () => api<Group>(`/api/core/groups/${id}`) })
}

/** Every group write returns the group; the cache takes it, and the list refreshes. */
function useGroupMutation<V>(write: (vars: V) => Promise<Group | void>) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: write,
    onSuccess: (group) => {
      if (group) client.setQueryData(['groups', group.id], group)
      void client.invalidateQueries({ queryKey: ['groups'] })
    },
  })
}

export function useCreateGroup() {
  return useGroupMutation((body: { name: string; emoji?: string }) => api<Group>('/api/core/groups', { method: 'POST', json: body }))
}

export function useUpdateGroup(id: string) {
  return useGroupMutation((body: { name?: string; emoji?: string; archived?: boolean }) =>
    api<Group>(`/api/core/groups/${id}`, { method: 'PATCH', json: body }))
}

export function useAddMember(id: string) {
  return useGroupMutation((body: { name: string; email?: string }) =>
    api<Group>(`/api/core/groups/${id}/members`, { method: 'POST', json: body }))
}

export function useRemoveMember(id: string) {
  return useGroupMutation((memberId: string) => api<void>(`/api/core/groups/${id}/members/${memberId}`, { method: 'DELETE' }))
}

export function useGroupLink(id: string, enabled: boolean) {
  return useQuery({
    queryKey: ['groups', id, 'link'],
    queryFn: () => api<{ token: string }>(`/api/core/groups/${id}/link`),
    enabled,
  })
}

export function useResetLink(id: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => api<{ token: string }>(`/api/core/groups/${id}/link/reset`, { method: 'POST' }),
    onSuccess: (link) => client.setQueryData(['groups', id, 'link'], link),
  })
}

export function useInvite(token: string) {
  return useQuery({ queryKey: ['invite', token], queryFn: () => api<GroupInvite>(`/api/core/groups/join/${token}`), retry: false })
}

export function useJoin(token: string) {
  return useGroupMutation(() => api<Group>(`/api/core/groups/join/${token}`, { method: 'POST' }))
}

/** The full link people open to join. */
export function joinUrl(token: string): string {
  return `${location.origin}/join/${token}`
}
