import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { useSpaceId } from './money'

/** core-service InsightsService: a money health score and "Can I afford it?". Amounts in paise. */
export interface HealthBasis { income: number; spent: number; months: number; cash: number | null; cardDues: number | null }
export interface HealthPart { key: string; title: string; points: number; max: number; status: 'good' | 'fair' | 'weak'; detail: string; tip: string }
export interface Health { score: number | null; grade: string; basis: HealthBasis; parts: HealthPart[] }
export interface Afford { verdict: 'YES' | 'MAYBE' | 'NO'; headline: string; reasons: string[]; safeToSpend: number | null; basis: HealthBasis }

export function useHealth() {
  const spaceId = useSpaceId()
  return useQuery({
    queryKey: ['money', spaceId, 'insights', 'health'],
    queryFn: () => api<Health>(`/api/core/spaces/${spaceId}/insights/health`),
    enabled: !!spaceId,
  })
}

export function affordCheck(spaceId: string, amount: number, monthly: boolean) {
  return api<Afford>(`/api/core/spaces/${spaceId}/insights/afford?amount=${amount}&monthly=${monthly}`)
}

/** A month looked back on (core-service MonthReview). */
export interface MonthReview {
  month: string; currency: string; income: number; spent: number; saved: number; incomeBefore: number; spentBefore: number
  entries: number; noSpendDays: number; days: number
  categories: { categoryId: string; amount: number; before: number }[]
  biggest: { id: string; description: string; amount: number; occurredOn: string; categoryId: string }[]
  budgetsSet: number; budgetsKept: number; highlights: string[]
}

/** @param month yyyy-mm; undefined for last month */
export function useMonthReview(month?: string) {
  const spaceId = useSpaceId()
  return useQuery({
    queryKey: ['money', spaceId, 'insights', 'review', month ?? 'last'],
    queryFn: () => api<MonthReview>(`/api/core/spaces/${spaceId}/insights/review${month ? `?month=${month}` : ''}`),
    enabled: !!spaceId,
  })
}
