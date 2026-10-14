import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'

export type InboxKind = 'GROUP_EXPENSE' | 'SETTLEMENT' | 'GROUP_MEMBER' | 'BILL_DUE' | 'BILL_OVERDUE' | 'BUDGET' | 'SIGN_IN' | 'RECAP' | 'LOAN_DUE' | 'AUTO_PAID'

export interface InboxItem {
  id: string
  kind: InboxKind
  title: string
  body: string | null
  link: string | null
  createdAt: string
  readAt: string | null
}

/**
 * How often the bell asks for news while the tab is open: a few seconds, so when two people are in the same group
 * one sees the other's expense almost at once. Hidden tabs don't ask (react-query pauses), and asking is one indexed count.
 */
export const INBOX_POLL_MS = 5_000

export function useUnreadCount() {
  return useQuery({
    queryKey: ['inbox', 'unread'],
    queryFn: () => api<{ unread: number }>('/api/core/me/inbox/unread'),
    refetchInterval: INBOX_POLL_MS,
    refetchOnWindowFocus: true,
  })
}

export function useInbox(enabled = true) {
  return useQuery({
    queryKey: ['inbox', 'list'],
    queryFn: () => api<{ unread: number; items: InboxItem[] }>('/api/core/me/inbox?limit=30'),
    enabled,
  })
}

export function useMarkRead() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (ids?: string[]) => api<{ unread: number }>('/api/core/me/inbox/read', { method: 'POST', json: ids ? { ids } : {} }),
    onSuccess: (r) => {
      client.setQueryData(['inbox', 'unread'], r)
      void client.invalidateQueries({ queryKey: ['inbox', 'list'] })
    },
  })
}
