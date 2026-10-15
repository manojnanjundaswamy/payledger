import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'

/** This month's AI reads: Free has a limit, Premium none (limit null). */
export interface AiUsage { limit: number | null; used: number }

export interface AiStatus {
  /** AI reading is set up on this server. */
  enabled: boolean
  /** The server can turn a recording into text, for browsers that can't (Firefox). */
  speechToText: boolean
  usage: AiUsage
}

/** One entry read from what was typed or said: ADDED already (undo with its version), or a DRAFT to check and add. */
export interface QuickEntry {
  status: 'ADDED' | 'DRAFT'
  kind: 'EXPENSE' | 'INCOME' | 'TRANSFER'
  amount: number
  currency: string
  occurredOn: string
  description: string
  counterparty: string | null
  accountId: string | null
  toAccountId: string | null
  categoryId: string | null
  /** 0..1: ours, after checking the AI's answer against what was said. 0.95 and up is added on its own. */
  confidence: number
  missing: string[]
  /** Why a draft wasn't added on its own, in plain words. */
  why: string[]
  transactionId: string | null
  version: number | null
  duplicateOf: string | null
}

export interface QuickAddResult {
  /** What was said, with spelling, grammar and spoken numbers fixed. */
  cleanText: string
  /** false: the simple reading (AI off, or this month's Free AI reads used); {@code notice} says which. */
  ai: boolean
  notice: string | null
  entries: QuickEntry[]
  usage: AiUsage
}

export function useAiStatus() {
  return useQuery({ queryKey: ['ai-status'], queryFn: () => api<AiStatus>('/api/core/ai/status'), staleTime: 60_000 })
}

export function useQuickAdd() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ text, channel }: { text: string; channel: 'TYPED' | 'VOICE' }) =>
      api<QuickAddResult>('/api/core/ai/quick-add', { method: 'POST', json: { text, channel } }),
    onSuccess: (r) => {
      client.setQueryData<AiStatus>(['ai-status'], (s) => (s ? { ...s, usage: r.usage } : s))
      // Something may have been added: every list and total refreshes.
      if (r.entries.some((e) => e.status === 'ADDED')) void client.invalidateQueries()
    },
  })
}

/** For browsers without speech recognition: the recording goes to the server once, comes back as text, and isn't kept. */
export async function transcribe(audio: Blob, seconds: number, language?: string): Promise<string> {
  const form = new FormData()
  const ext = audio.type.includes('mp4') ? 'mp4' : audio.type.includes('ogg') ? 'ogg' : 'webm'
  form.append('audio', audio, `speech.${ext}`)
  form.append('seconds', String(Math.round(seconds * 10) / 10))
  if (language) form.append('language', language)
  const r = await api<{ text: string }>('/api/core/ai/transcribe', { method: 'POST', body: form })
  return r.text
}
