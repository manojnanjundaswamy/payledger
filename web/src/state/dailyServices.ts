import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { useSpaceId } from './money'

/** core-service DailyServices: milk, newspaper, cook, maid. Amounts in paise. */
export interface DailyService {
  id: string; name: string; emoji: string | null; rate: number; per: 'DAY' | 'MONTH'; paidLeaves: number
  accountId: string | null; categoryId: string | null; startedOn: string; endedOn: string | null
}
/** marks: only the days that differ from normal, date → quantity (0 missed, 2+ extra). */
export interface ServiceBill {
  month: string; days: number; units: number; missed: number; extra: number; amount: number; soFar: number
  paid: { transactionId: string; amount: number } | null; marks: Record<string, number>
}
export interface ServiceRow { service: DailyService; bill: ServiceBill }
export type ServiceFields = Partial<Omit<DailyService, 'id'>>

const base = (space: string | undefined) => `/api/core/spaces/${space}/daily-services`

export function useDailyServices(month: string) {
  const spaceId = useSpaceId()
  return useQuery({
    queryKey: ['money', spaceId, 'daily-services', month],
    queryFn: () => api<ServiceRow[]>(`${base(spaceId)}?month=${month}`),
    enabled: !!spaceId,
  })
}

function useServiceWrite<V>(write: (space: string, vars: V) => Promise<unknown>, onlyServices = false) {
  const spaceId = useSpaceId()
  const client = useQueryClient()
  return useMutation({
    mutationFn: (vars: V) => write(spaceId!, vars),
    // Paying records an expense: balances, budgets and lists change too. Marking a day changes only the bills.
    onSettled: () => void client.invalidateQueries({ queryKey: onlyServices ? ['money', spaceId, 'daily-services'] : ['money', spaceId] }),
  })
}

export function useSaveService() {
  return useServiceWrite((space, { id, fields }: { id?: string; fields: ServiceFields }) =>
    api<ServiceRow>(id ? `${base(space)}/${id}` : base(space), { method: id ? 'PATCH' : 'POST', json: fields }))
}

export function useRemoveService() {
  return useServiceWrite((space, id: string) => api<void>(`${base(space)}/${id}`, { method: 'DELETE' }))
}

export function useMarkDay() {
  return useServiceWrite((space, { id, day, quantity }: { id: string; day: string; quantity: number }) =>
    api<ServiceRow>(`${base(space)}/${id}/days/${day}`, { method: 'PUT', json: { quantity } }), true)
}

export function usePayService() {
  return useServiceWrite((space, { id, month, accountId }: { id: string; month: string; accountId?: string }) =>
    api<ServiceRow>(`${base(space)}/${id}/payments`, { method: 'POST', json: { month, accountId } }))
}

export function useUnpayService() {
  return useServiceWrite((space, { id, month }: { id: string; month: string }) =>
    api<ServiceRow>(`${base(space)}/${id}/payments/${month}`, { method: 'DELETE' }))
}
