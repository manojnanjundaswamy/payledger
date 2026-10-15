import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'

/** An entry read from a bank alert, waiting to be added (or already decided, for a couple of days). */
export interface CapturedItem {
  id: string
  source: 'EMAIL' | 'PASTE' | 'SHARE' | 'STATEMENT'
  status: 'PENDING' | 'ADDED' | 'DISMISSED' | 'UNREADABLE'
  sender: string | null
  subject: string | null
  /** The original text, until it's added or dismissed. */
  body: string | null
  kind: 'EXPENSE' | 'INCOME' | null
  amount: number | null
  occurredOn: string | null
  merchant: string | null
  bank: string | null
  accountHint: string | null
  balance: number | null
  transactionId: string | null
  receivedAt: string
  /** Guesses: the account (bank or last digits in its name), the category, and a transaction that looks like the same one. */
  accountId: string | null
  categoryId: string | null
  categorySource: string | null
  duplicateOf: string | null
  /** RULES: the built-in reader; LEARNED: a pattern you taught it by filling one in. */
  readBy: 'RULES' | 'LEARNED' | null
  /** Set on the answer to "add": what this taught it (a pattern, or a name), if anything. */
  learned?: 'pattern' | 'name' | null
}

export interface CaptureView {
  address: string
  /** Email forwarding is switched on for this site (Cloudflare Email Routing and the Worker are set up). */
  emailReady: boolean
  confirmation: { code: string | null; link: string | null; receivedAt: string } | null
  /** Gmail forwarding works: an email came through after Gmail's confirmation, or you said you confirmed it. */
  forwardingOn: boolean
  pending: number
  items: CapturedItem[]
  lastEmailAt: string | null
}

export interface Pasted { added: number; duplicates: number; unreadable: number; items: CapturedItem[] }

export function useCapture(poll = false) {
  return useQuery({
    queryKey: ['capture'],
    queryFn: () => api<CaptureView>('/api/core/capture'),
    // While waiting for Gmail's code or the first email, look again every 10 seconds.
    refetchInterval: poll ? 10_000 : false,
  })
}

export function useCaptureCount() {
  return useQuery({ queryKey: ['capture', 'count'], queryFn: () => api<{ pending: number }>('/api/core/capture/count'), refetchInterval: 60_000 })
}

function useCaptureWrite<V, R>(write: (v: V) => Promise<R>) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: write,
    onSettled: () => {
      void client.invalidateQueries({ queryKey: ['capture'] })
      void client.invalidateQueries({ queryKey: ['money'] })
    },
  })
}

export function usePaste() {
  return useCaptureWrite((body: { text: string; source?: 'PASTE' | 'SHARE' }) => api<Pasted>('/api/core/capture/paste', { method: 'POST', json: body }))
}

export interface AddCaptured {
  id: string
  kind: 'EXPENSE' | 'INCOME'
  amount: number
  accountId: string
  categoryId: string | null
  occurredOn: string
  description: string
  counterparty?: string | null
}

export function useAddCaptured() {
  return useCaptureWrite(({ id, ...body }: AddCaptured) => api<CapturedItem>(`/api/core/capture/items/${id}/add`, { method: 'POST', json: body }))
}

export interface Imported { rows: number; added: number; duplicates: number; bank: string | null; from: string; to: string; opening: number | null; closing: number | null; adds: boolean | null }

export function useStatement() {
  return useCaptureWrite(({ file, accountId, password }: { file: File; accountId: string; password?: string }) => {
    const form = new FormData()
    form.append('file', file)
    form.append('accountId', accountId)
    if (password) form.append('password', password)
    return api<Imported>('/api/core/capture/statement', { method: 'POST', body: form })
  })
}

export function useAddAll() {
  return useCaptureWrite((ids: string[]) => api<{ added: number; skipped: number }>('/api/core/capture/items/add', { method: 'POST', json: { ids } }))
}

export function useDismissCaptured() {
  return useCaptureWrite((id: string) => api<CapturedItem>(`/api/core/capture/items/${id}/dismiss`, { method: 'POST' }))
}

export function useForwardingConfirmed() {
  return useCaptureWrite(() => api<CaptureView>('/api/core/capture/address/confirmed', { method: 'POST' }))
}

export function useResetAddress() {
  return useCaptureWrite(() => api<CaptureView>('/api/core/capture/address/reset', { method: 'POST' }))
}

/** Gmail filter for the common banks' alert senders (old domains and RBI's new .bank.in ones). */
export const BANK_FILTER = 'from:(hdfcbank.net OR hdfcbank.bank.in OR icicibank.com OR icici.bank.in OR sbi.co.in OR sbi.bank.in OR axisbank.com OR axis.bank.in OR kotak.com OR kotak.bank.in OR yesbank.in OR idfcfirstbank.com OR indusind.com OR federalbank.co.in OR aubank.in OR americanexpress.com OR sc.com)'
