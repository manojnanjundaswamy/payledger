import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'

export type EmailKind = 'BILL_REMINDERS' | 'BUDGET_ALERTS' | 'GROUP_ACTIVITY' | 'PRODUCT_NEWS'
export type EmailPrefs = Record<EmailKind, boolean>

export interface SentEmail { kind: string; subject: string; created_at: string; sent_at: string | null; attempts: number; last_error: string | null }

export function useEmailPrefs() {
  return useQuery({ queryKey: ['notifications', 'prefs'], queryFn: () => api<EmailPrefs>('/api/core/me/notifications') })
}

export function useSetEmailPrefs() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (change: Partial<EmailPrefs>) => api<EmailPrefs>('/api/core/me/notifications', { method: 'PATCH', json: change }),
    onSuccess: (prefs) => client.setQueryData(['notifications', 'prefs'], prefs),
  })
}

export function useSentEmails() {
  return useQuery({ queryKey: ['notifications', 'sent'], queryFn: () => api<SentEmail[]>('/api/core/me/notifications/sent'), refetchInterval: 5_000 })
}

export function useTestEmail() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => api<void>('/api/core/me/notifications/test', { method: 'POST' }),
    onSuccess: () => void client.invalidateQueries({ queryKey: ['notifications', 'sent'] }),
  })
}
