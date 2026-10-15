import { api } from '@/lib/api'
import type { AiUsage } from './ai'

/** A proposed action from voice mode: checked by the server, run by the app's own API only when confirmed. */
export interface Proposal {
  id: string
  type: 'TRANSACTION' | 'BUDGET' | 'BILL' | 'PAY_BILL' | 'GOAL'
  title: string
  summary: string
  amount: number | null
  currency: string | null
  /** 0..1 */
  confidence: number
  /** Every required field is known; else {@code why} says what's missing. */
  ready: boolean
  why: string[]
  body: Record<string, unknown>
  /** Corrects the shown proposal at this position ("make it 500"). */
  replaces: number | null
  /** Where it lives in the app. */
  page: string
}

export interface AssistantReply {
  requestId: string
  say: string
  heard: string | null
  intent: 'CONFIRM' | 'CANCEL' | 'UNDO' | 'STOP' | null
  proposals: Proposal[]
  navigate: string | null
  usage: AiUsage
}

export interface Turn { role: 'person' | 'assistant'; text: string }

export function askAssistant(turns: Turn[], shown: Proposal[], page: string, channel: 'VOICE' | 'TYPED') {
  return api<AssistantReply>('/api/core/ai/assistant', {
    method: 'POST',
    json: { turns: turns.slice(-8), shown: shown.map((p, index) => ({ index, type: p.type, summary: `${p.title}: ${p.summary}` })), page, channel },
  })
}

/** How to take back what a proposal did. */
export type UndoStep = () => Promise<unknown>

/** Runs one confirmed proposal through the same API the app's forms use; returns how to undo it. */
export async function runProposal(space: string, p: Proposal): Promise<UndoStep> {
  const base = `/api/core/spaces/${space}`
  const b = p.body
  switch (p.type) {
    case 'TRANSACTION': {
      const t = await api<{ id: string; version: number }>(`${base}/transactions`, { method: 'POST', json: { id: crypto.randomUUID(), ...b } })
      return () => api(`${base}/transactions/${t.id}?version=${t.version}`, { method: 'DELETE' })
    }
    case 'BUDGET': {
      const { categoryId, amount, from, previous } = b as { categoryId: string; amount: number; from: string; previous: number | null }
      await api(`${base}/budgets/${categoryId}`, { method: 'PUT', json: { amount, from } })
      return () => previous
        ? api(`${base}/budgets/${categoryId}`, { method: 'PUT', json: { amount: previous, from } })
        : api(`${base}/budgets/${categoryId}?from=${from}`, { method: 'DELETE' })
    }
    case 'BILL': {
      const c = await api<{ id: string }>(`${base}/commitments`, { method: 'POST', json: b })
      return () => api(`${base}/commitments/${c.id}`, { method: 'DELETE' })
    }
    case 'PAY_BILL': {
      const { commitmentId, dueDate, amount, occurredOn } = b as { commitmentId: string; dueDate: string; amount: number | null; occurredOn: string | null }
      await api(`${base}/commitments/${commitmentId}/payments`, { method: 'POST', json: { dueDate, amount, occurredOn } })
      return () => api(`${base}/commitments/${commitmentId}/payments/${dueDate}`, { method: 'DELETE' })
    }
    case 'GOAL': {
      const g = await api<{ id: string }>(`${base}/goals`, { method: 'POST', json: b })
      return () => api(`${base}/goals/${g.id}`, { method: 'DELETE' })
    }
  }
}

/**
 * A plain yes or no answered on the device, instantly and for free: no round trip for "add them" or "cancel".
 * Anything longer ("yes but make the petrol one 500") goes to the assistant.
 */
export function localIntent(said: string): 'CONFIRM' | 'CANCEL' | 'UNDO' | 'STOP' | null {
  const s = said.toLowerCase().replace(/[.,!?]/g, ' ').replace(/\s+/g, ' ').trim()
  if (/^(yes|yeah|yep|yup|ok|okay|sure|confirm|go ahead|do it|done|save|save (it|them|all|everything)|add (it|them|all|everything|all of them)|yes (please|add (it|them)|save (it|them))|haan|ha|theek hai)$/.test(s)) return 'CONFIRM'
  if (/^(no|nope|cancel|cancel (it|that|them|all)|leave it|never ?mind|don'?t|discard( (it|them))?|nahi)$/.test(s)) return 'CANCEL'
  if (/^(undo|undo (it|that|this|them|all|everything)|take (it|that) back)$/.test(s)) return 'UNDO'
  if (/^(stop|stop listening|bye|goodbye|that'?s all|exit|close|i'?m done)$/.test(s)) return 'STOP'
  return null
}
