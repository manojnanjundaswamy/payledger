import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'

/** A group you share with someone, and what you and they owe each other in it (positive: they owe you). */
export interface SharedGroup { groupId: string; name: string; emoji: string | null; direct: boolean; currency: string; memberId: string; net: number }

/** Someone you share money with: a registered user, an email, or a name in one group. */
export interface Person {
  key: string
  name: string
  email: string | null
  registered: boolean
  /** Paise in rupees across groups: positive, they owe you; negative, you owe them. */
  net: number
  groups: SharedGroup[]
  /** Your one-to-one ledger with them (loans), once there is one. */
  directId: string | null
  nextDue: string | null
  lastActivity: string | null
  upiId: string | null
}

export interface People { totals: { owedToYou: number; youOwe: number }; people: Person[] }

export function usePeople() {
  return useQuery({ queryKey: ['people'], queryFn: () => api<People>('/api/core/people') })
}

export interface Loan {
  id: string
  personKey?: string | null
  name?: string | null
  email?: string | null
  direction: 'LENT' | 'BORROWED'
  amount: number
  occurredOn: string
  dueOn?: string | null
  note?: string | null
  accountId?: string | null
}

export function useLend() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (loan: Loan) => api<{ id: string }>('/api/core/people/loans', { method: 'POST', json: loan }),
    onSettled: () => {
      void client.invalidateQueries({ queryKey: ['people'] })
      void client.invalidateQueries({ queryKey: ['groups'] })
      void client.invalidateQueries({ queryKey: ['money'] })
    },
  })
}
