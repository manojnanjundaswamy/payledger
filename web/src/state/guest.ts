import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type { ExpenseFields, Group, GroupActivity, GroupBalances, GroupExpense, Settlement } from './groups'

/**
 * Guests: people in a group without an account (core-service GuestController). They prove their email with a code
 * and get a guest session cookie; these calls use it. Same shapes as the signed-in groups API.
 */
export interface GuestLink { groupName: string; emoji: string | null; members: number; spots: { id: string; name: string }[]; guests: boolean }
export interface GuestMe { email: string; name: string | null; groups: { id: string; name: string; emoji: string | null; currency: string; you: string }[] }

const base = (groupId: string) => `/api/guest/groups/${groupId}`

export function useGuestLink(token: string) {
  return useQuery({ queryKey: ['guest', 'link', token], queryFn: () => api<GuestLink>(`/api/guest/links/${token}`), retry: false })
}

export function useSendGuestCode(token: string) {
  return useMutation({
    mutationFn: (body: { memberId?: string; name?: string; email: string }) =>
      api<{ codeId: string; email: string }>(`/api/guest/links/${token}/code`, { method: 'POST', json: body }),
  })
}

export function useVerifyGuestCode() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ codeId, code }: { codeId: string; code: string }) =>
      api<{ groupId: string }>(`/api/guest/codes/${codeId}`, { method: 'POST', json: { code } }),
    onSuccess: () => void client.invalidateQueries({ queryKey: ['guest'] }),
  })
}

export function useGuestMe() {
  return useQuery({ queryKey: ['guest', 'me'], queryFn: () => api<GuestMe>('/api/guest/me'), retry: false })
}

export function useGuestSignOut() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => api<void>('/api/guest/sign-out', { method: 'POST' }),
    onSettled: () => client.removeQueries({ queryKey: ['guest'] }),
  })
}

export function useGuestGroup(id: string) {
  return useQuery({ queryKey: ['guest', id], queryFn: () => api<Group>(base(id)), retry: false })
}

export function useGuestActivity(id: string) {
  return useQuery({ queryKey: ['guest', id, 'activity'], queryFn: () => api<GroupActivity>(`${base(id)}/activity`) })
}

export function useGuestBalances(id: string) {
  return useQuery({ queryKey: ['guest', id, 'balances'], queryFn: () => api<GroupBalances>(`${base(id)}/balances`) })
}

/** Balances come from the ledger a moment after a write, so they're asked again shortly after. */
function useGuestWrite<V, R>(id: string, write: (vars: V) => Promise<R>) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: write,
    onSettled: () => {
      void client.invalidateQueries({ queryKey: ['guest', id] })
      for (const delay of [800, 2500]) setTimeout(() => void client.invalidateQueries({ queryKey: ['guest', id, 'balances'] }), delay)
    },
  })
}

export function useGuestAddExpense(id: string) {
  return useGuestWrite(id, (body: ExpenseFields & { id: string }) => api<GroupExpense>(`${base(id)}/expenses`, { method: 'POST', json: body }))
}

export function useGuestSettle(id: string) {
  return useGuestWrite(id, (body: { fromMemberId: string; toMemberId: string; amount: number; occurredOn: string; note?: string }) =>
    api<Settlement>(`${base(id)}/settlements`, { method: 'POST', json: body }))
}
