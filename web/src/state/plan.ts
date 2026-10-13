import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'

export interface PlanStatus {
  plan: 'FREE' | 'PREMIUM'
  /** What gave Premium: TRIAL, PAID, GRANTED (given by PayLedger until a date) or COMPLIMENTARY. */
  source: 'TRIAL' | 'PAID' | 'GRANTED' | 'COMPLIMENTARY' | null
  premiumUntil: string | null
  trialAvailable: boolean
  /** Free's limits by key (null on Premium), and how much of each is used. */
  limits: Record<string, number> | null
  usage: Record<string, number>
}

export const planQuery = queryOptions({
  queryKey: ['plan'],
  queryFn: () => api<PlanStatus>('/api/core/plan'),
})

export function usePlan() {
  return useQuery(planQuery)
}

/** 30 days of Premium, once, no card. */
export function useStartTrial() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => api<PlanStatus>('/api/core/plan/trial', { method: 'POST' }),
    onSuccess: (status) => client.setQueryData(planQuery.queryKey, status),
  })
}
