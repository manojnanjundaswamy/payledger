import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { useSpaceId } from './money'

export type GoalStatus = 'REACHED' | 'ON_TRACK' | 'BEHIND' | 'OPEN'

/** A savings goal; amounts are paise. saved is null when its linked account's balance is unavailable. */
export interface Goal {
  id: string; name: string; emoji: string | null; target: number; targetDate: string | null; accountId: string | null
  version: number; startedOn: string; archived: boolean; saved: number | null; perMonth: number | null
  status: GoalStatus; behindBy: number | null
  /** Transfers and income marked for the goal (paise), and expenses marked for it. */
  fromEntries: number; spent: number
  /** A saving on the plan is made for it. */
  planned: boolean
}

export interface GoalContribution { id: string; amount: number; occurredOn: string; note: string | null }

export interface GoalFields { name: string; emoji?: string | null; target: number; targetDate?: string | null; accountId?: string | null }

const path = (spaceId: string) => `/api/core/spaces/${spaceId}/goals`

export function useGoals(includeArchived = false) {
  const spaceId = useSpaceId()
  return useQuery({
    queryKey: ['money', spaceId, 'goals', { includeArchived }],
    queryFn: () => api<Goal[]>(`${path(spaceId!)}?includeArchived=${includeArchived}`),
    enabled: !!spaceId,
  })
}

export function useGoalContributions(id: string | undefined) {
  const spaceId = useSpaceId()
  return useQuery({
    queryKey: ['money', spaceId, 'goals', id, 'contributions'],
    queryFn: () => api<GoalContribution[]>(`${path(spaceId!)}/${id}/contributions`),
    enabled: !!spaceId && !!id,
  })
}

function useGoalWrite<V, R>(write: (spaceId: string, vars: V) => Promise<R>) {
  const spaceId = useSpaceId()
  const client = useQueryClient()
  return useMutation({
    mutationFn: (vars: V) => write(spaceId!, vars),
    onSettled: () => void client.invalidateQueries({ queryKey: ['money', spaceId, 'goals'] }),
  })
}

export function useCreateGoal() {
  return useGoalWrite((spaceId, body: GoalFields) => api<Goal>(path(spaceId), { method: 'POST', json: body }))
}

export function useUpdateGoal() {
  return useGoalWrite((spaceId, { id, version, fields, archived }: { id: string; version: number; fields: GoalFields; archived?: boolean }) =>
    api<Goal>(`${path(spaceId)}/${id}`, { method: 'PATCH', json: { version, ...fields, archived } }))
}

export function useDeleteGoal() {
  return useGoalWrite((spaceId, id: string) => api<void>(`${path(spaceId)}/${id}`, { method: 'DELETE' }))
}

export function useContribute() {
  return useGoalWrite((spaceId, { id, amount, note }: { id: string; amount: number; note?: string }) =>
    api<Goal>(`${path(spaceId)}/${id}/contributions`, { method: 'POST', json: { amount, note } }))
}

export function useDeleteContribution() {
  return useGoalWrite((spaceId, { id, contributionId }: { id: string; contributionId: string }) =>
    api<void>(`${path(spaceId)}/${id}/contributions/${contributionId}`, { method: 'DELETE' }))
}
