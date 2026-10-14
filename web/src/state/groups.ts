import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'

/** core-service's groups API (docs/adr/0007-groups-and-splitting.md). */

/** net: what you owe the group (positive) or are owed (negative), paise; spent: all its expenses. */
export interface GroupSummary {
  id: string; name: string; emoji: string | null; currency: string; role: 'OWNER' | 'MEMBER'; members: number; archived: boolean; you: string
  spent: number; expenses: number; net: number; lastActivity: string | null
}

/** A group's totals for charts (paise). */
export interface GroupInsights {
  currency: string; spent: number; expenses: number; settled: number; yourPaid: number; yourShare: number
  members: { memberId: string; name: string; left: boolean; paid: number; share: number; expenses: number }[]
  labels: { label: string | null; amount: number; count: number }[]
  months: { month: string; amount: number; count: number }[]
  biggest: { id: string; description: string; amount: number; occurredOn: string }[]
}

export function useInsights(id: string) {
  return useQuery({ queryKey: ['groups', id, 'insights'], queryFn: () => api<GroupInsights>(`/api/core/groups/${id}/insights`) })
}

/** A person in a group: joined (has an account) or a placeholder, maybe invited by email. */
export interface GroupMember { id: string; name: string; email: string | null; role: 'OWNER' | 'MEMBER'; joined: boolean; you: boolean; upiId?: string | null }

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

export type SplitType = 'EQUAL' | 'EXACT' | 'PERCENT' | 'SHARES'

/** A member's part of an expense: what they paid and their share (paise), and what was typed for the split. */
export interface ExpensePart { memberId: string; paid: number; share: number; input: number | null }

export interface GroupExpense {
  id: string; description: string; amount: number; currency: string; occurredOn: string; category: string | null
  split: SplitType; version: number; parts: ExpensePart[]
}

export interface Settlement { id: string; fromMemberId: string; toMemberId: string; amount: number; currency: string; occurredOn: string; note: string | null }

export interface GroupActivity { expenses: GroupExpense[]; settlements: Settlement[] }

/** balance: positive owes the group, negative is owed. payments: the fewest that settle everyone. */
export interface GroupBalances {
  known: boolean
  currency: string
  balance: Record<string, number> | null
  payments: { from: string; to: string; amount: number }[]
}

export interface ExpenseFields {
  description: string
  amount: number
  occurredOn: string
  category?: string | null
  paidBy: { memberId: string; value: number }[]
  split: SplitType
  shares: { memberId: string; value?: number }[]
}

export function useActivity(id: string) {
  return useQuery({ queryKey: ['groups', id, 'activity'], queryFn: () => api<GroupActivity>(`/api/core/groups/${id}/activity`) })
}

export function useBalances(id: string) {
  return useQuery({ queryKey: ['groups', id, 'balances'], queryFn: () => api<GroupBalances>(`/api/core/groups/${id}/balances`) })
}

/** After a write: activity at once; balances come from the ledger a moment later, so again shortly after. */
function useMoneyWrite<V, R>(id: string, write: (vars: V) => Promise<R>) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: write,
    onSettled: () => {
      void client.invalidateQueries({ queryKey: ['groups', id] })
      for (const delay of [800, 2500]) {
        setTimeout(() => void client.invalidateQueries({ queryKey: ['groups', id, 'balances'] }), delay)
      }
    },
  })
}

export function useAddExpense(id: string) {
  return useMoneyWrite(id, (body: ExpenseFields & { id: string }) =>
    api<GroupExpense>(`/api/core/groups/${id}/expenses`, { method: 'POST', json: body }))
}

export function useUpdateExpense(id: string) {
  return useMoneyWrite(id, ({ expenseId, version, fields }: { expenseId: string; version: number; fields: ExpenseFields }) =>
    api<GroupExpense>(`/api/core/groups/${id}/expenses/${expenseId}?version=${version}`, { method: 'PUT', json: fields }))
}

export function useDeleteExpense(id: string) {
  return useMoneyWrite(id, ({ expenseId, version }: { expenseId: string; version: number }) =>
    api<void>(`/api/core/groups/${id}/expenses/${expenseId}?version=${version}`, { method: 'DELETE' }))
}

export function useSettle(id: string) {
  return useMoneyWrite(id, (body: { fromMemberId: string; toMemberId: string; amount: number; occurredOn: string; note?: string }) =>
    api<Settlement>(`/api/core/groups/${id}/settlements`, { method: 'POST', json: body }))
}

export function useDeleteSettlement(id: string) {
  return useMoneyWrite(id, (settlementId: string) => api<void>(`/api/core/groups/${id}/settlements/${settlementId}`, { method: 'DELETE' }))
}

/**
 * The same equal split as core-service (paise rounded down, leftovers one each from the top), so the form
 * can show each person's share before saving.
 */
export function previewSplit(amount: number, split: SplitType, parts: { memberId: string; value: number }[]): Record<string, number> | null {
  if (parts.length === 0 || amount <= 0) return null
  if (split === 'EXACT') return Object.fromEntries(parts.map((p) => [p.memberId, p.value]))
  const weights = parts.map((p) => (split === 'EQUAL' ? 1 : p.value))
  const total = weights.reduce((s, w) => s + w, 0)
  if (total <= 0 || (split === 'PERCENT' && total !== 10_000)) return null
  const shares = weights.map((w) => Math.floor((amount * w) / total))
  let left = amount - shares.reduce((s, x) => s + x, 0)
  for (let i = 0; left > 0; i = (i + 1) % shares.length) {
    if (weights[i]! > 0) { shares[i]! += 1; left-- }
  }
  return Object.fromEntries(parts.map((p, i) => [p.memberId, shares[i]!]))
}
