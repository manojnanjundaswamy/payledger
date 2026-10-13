import { infiniteQueryOptions, queryOptions, useInfiniteQuery, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { useProfile } from './profile'

/** core-service's money API (README "API"). Amounts are paise. */

export type AccountKind = 'BANK' | 'CASH' | 'CARD' | 'WALLET'
export type TxnKind = 'EXPENSE' | 'INCOME' | 'TRANSFER'

export interface Account {
  id: string
  kind: AccountKind
  name: string
  currency: string
  /** In the user's terms: money in it, or for a CARD what you owe. */
  openingBalance: number
  openingDate: string
  archived: boolean
  /** Same terms as openingBalance. Null when the ledger couldn't be reached. */
  balance: number | null
}

export interface Category {
  id: string
  kind: 'EXPENSE' | 'INCOME'
  name: string
  icon: string
  color: string
  parentId: string | null
  hidden: boolean
}

export interface Transaction {
  id: string
  kind: TxnKind
  amount: number
  currency: string
  accountId: string
  toAccountId: string | null
  categoryId: string | null
  occurredOn: string
  description: string
  note: string | null
  tags: string[]
  version: number
}

export interface TransactionFields {
  kind: TxnKind
  amount: number
  accountId: string
  toAccountId?: string | null
  categoryId?: string | null
  occurredOn: string
  description: string
  note?: string | null
  tags?: string[]
}

export interface TransactionFilter {
  accountId?: string
  categoryId?: string
  from?: string
  to?: string
}

interface Page { items: Transaction[]; nextCursor: string | null }

/** Money lives in a space; for now that is always the personal one (households come in Phase 5). */
export function useSpaceId(): string | undefined {
  return useProfile().data?.spaces.find((s) => s.kind === 'PERSONAL')?.id
}

const base = (spaceId: string) => `/api/core/spaces/${spaceId}`

export const accountsQuery = (spaceId: string, includeArchived = false) => queryOptions({
  queryKey: ['money', spaceId, 'accounts', { includeArchived }],
  queryFn: () => api<Account[]>(`${base(spaceId)}/accounts?includeArchived=${includeArchived}`),
})

export const categoriesQuery = (spaceId: string) => queryOptions({
  queryKey: ['money', spaceId, 'categories'],
  queryFn: () => api<Category[]>(`${base(spaceId)}/categories`),
  staleTime: 10 * 60_000,
})

export const transactionsQuery = (spaceId: string, filter: TransactionFilter) => infiniteQueryOptions({
  queryKey: ['money', spaceId, 'transactions', filter],
  initialPageParam: null as string | null,
  queryFn: ({ pageParam }) => {
    const params = new URLSearchParams({ limit: '50' })
    for (const [k, v] of Object.entries(filter)) if (v) params.set(k, v)
    if (pageParam) params.set('cursor', pageParam)
    return api<Page>(`${base(spaceId)}/transactions?${params}`)
  },
  getNextPageParam: (last) => last.nextCursor,
})

export function useAccounts(includeArchived = false) {
  const spaceId = useSpaceId()
  return useQuery({ ...accountsQuery(spaceId ?? '', includeArchived), enabled: !!spaceId })
}

export function useCategories() {
  const spaceId = useSpaceId()
  return useQuery({ ...categoriesQuery(spaceId ?? ''), enabled: !!spaceId })
}

export function useTransactions(filter: TransactionFilter) {
  const spaceId = useSpaceId()
  return useInfiniteQuery({ ...transactionsQuery(spaceId ?? '', filter), enabled: !!spaceId })
}

/**
 * After a write, lists refresh at once; balances come from the ledger, which applies the change
 * a moment later (core-service -> Kafka -> ledger), so they are fetched again shortly after.
 */
function refreshAfterWrite(client: QueryClient, spaceId: string) {
  void client.invalidateQueries({ queryKey: ['money', spaceId] })
  for (const delay of [800, 2500]) {
    setTimeout(() => void client.invalidateQueries({ queryKey: ['money', spaceId, 'accounts'] }), delay)
  }
}

function useMoneyMutation<V, R>(write: (spaceId: string, vars: V) => Promise<R>) {
  const spaceId = useSpaceId()
  const client = useQueryClient()
  return useMutation({
    mutationFn: (vars: V) => {
      if (!spaceId) throw new Error('no space')
      return write(spaceId, vars)
    },
    onSettled: () => spaceId && refreshAfterWrite(client, spaceId),
  })
}

export function useCreateAccount() {
  return useMoneyMutation((spaceId, body: { kind: AccountKind; name: string; openingBalance: number; openingDate?: string }) =>
    api<Account>(`${base(spaceId)}/accounts`, { method: 'POST', json: body }))
}

export function useUpdateAccount() {
  return useMoneyMutation((spaceId, { id, ...body }: { id: string; name?: string; openingBalance?: number; openingDate?: string; archived?: boolean }) =>
    api<Account>(`${base(spaceId)}/accounts/${id}`, { method: 'PATCH', json: body }))
}

/**
 * `id` is chosen by the form once per new transaction, so a double tap or a retry after a
 * timeout sends the same id and core-service adds it only once.
 */
export function useCreateTransaction() {
  return useMoneyMutation((spaceId, body: TransactionFields & { id: string }) =>
    api<Transaction>(`${base(spaceId)}/transactions`, { method: 'POST', json: body }))
}

export function useUpdateTransaction() {
  return useMoneyMutation((spaceId, { id, version, fields }: { id: string; version: number; fields: TransactionFields }) =>
    api<Transaction>(`${base(spaceId)}/transactions/${id}`, { method: 'PUT', json: { version, ...fields } }))
}

export function useDeleteTransaction() {
  return useMoneyMutation((spaceId, { id, version }: { id: string; version: number }) =>
    api<void>(`${base(spaceId)}/transactions/${id}?version=${version}`, { method: 'DELETE' }))
}
