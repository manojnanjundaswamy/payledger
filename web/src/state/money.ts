import { infiniteQueryOptions, queryOptions, useInfiniteQuery, useMutation, useQueries, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
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
 * After a write, lists refresh at once; balances and category totals come from the ledger, which
 * applies the change a moment later (core-service -> Kafka -> ledger), so they are fetched again shortly after.
 */
function refreshAfterWrite(client: QueryClient, spaceId: string) {
  void client.invalidateQueries({ queryKey: ['money', spaceId] })
  const fromLedger = new Set(['accounts', 'category-totals', 'budgets', 'safe-to-spend', 'planner'])
  for (const delay of [800, 2500]) {
    setTimeout(() => void client.invalidateQueries({
      predicate: (q) => q.queryKey[0] === 'money' && q.queryKey[1] === spaceId && fromLedger.has(q.queryKey[2] as string),
    }), delay)
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

/** Spending or income per category, positive amounts (core-service reads them from the ledger). */
export interface CategoryTotal {
  categoryId: string
  currency: string
  /** First day of the month when grouped by month, else null. */
  month: string | null
  amount: number
}

export function useCategoryTotals(kind: 'EXPENSE' | 'INCOME', from: string, to: string, byMonth = false, enabled = true) {
  const spaceId = useSpaceId()
  return useQuery({
    queryKey: ['money', spaceId, 'category-totals', { kind, from, to, byMonth }],
    queryFn: () => api<CategoryTotal[]>(`${base(spaceId!)}/category-totals?${new URLSearchParams({ kind, from, to, byMonth: String(byMonth) })}`),
    enabled: !!spaceId && enabled,
  })
}

/** yyyy-mm-dd in local time (the business date the API expects). */
export function isoDate(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

export interface CategoryFields {
  name: string
  icon: string
  color: string
  parentId: string | null
}

export function useCreateCategory() {
  return useMoneyMutation((spaceId, body: CategoryFields & { kind: Category['kind'] }) =>
    api<Category>(`${base(spaceId)}/categories`, { method: 'POST', json: body }))
}

/** Sends the whole category; hiding or showing a parent does the same to its subcategories. */
export function useUpdateCategory() {
  return useMoneyMutation((spaceId, { id, ...body }: CategoryFields & { id: string; hidden: boolean }) =>
    api<Category>(`${base(spaceId)}/categories/${id}`, { method: 'PUT', json: body }))
}

/** Only works for a category no transaction ever used (409 category-in-use otherwise: hide it instead). */
export function useDeleteCategory() {
  return useMoneyMutation((spaceId, id: string) =>
    api<void>(`${base(spaceId)}/categories/${id}`, { method: 'DELETE' }))
}

export interface CategoryNode { category: Category; children: Category[] }

/**
 * Top-level categories in the API's order, each with its subcategories. A subcategory whose parent
 * isn't in the list (it shouldn't happen) is shown at the top level rather than lost.
 */
export function categoryTree(categories: Category[]): CategoryNode[] {
  const ids = new Set(categories.map((c) => c.id))
  return categories
    .filter((c) => !c.parentId || !ids.has(c.parentId))
    .map((category) => ({ category, children: categories.filter((c) => c.parentId === category.id) }))
}

/** "Groceries › Snacks" for a subcategory, so a choice is never ambiguous. */
export function categoryLabel(category: Category, categories: Category[]): string {
  const parent = category.parentId ? categories.find((c) => c.id === category.parentId) : undefined
  return parent ? `${parent.name} › ${category.name}` : category.name
}

export type BudgetStatus = 'ON_TRACK' | 'CLOSE' | 'OVER'

export interface Budget {
  categoryId: string
  limit: number
  currency: string
  /** Null when the ledger couldn't be reached. A parent's spending includes its subcategories'. */
  spent: number | null
  remaining: number | null
  status: BudgetStatus | null
  /** Spending by the end of the period at the current pace: current period, from its 5th day. */
  projected: number | null
}

/** One budget month: "2026-10" is the period that starts in October (on the user's start day). */
export interface BudgetMonth {
  month: string
  start: string
  end: string
  days: number
  daysElapsed: number
  today: string
  spentKnown: boolean
  budgets: Budget[]
}

/** `month` undefined: the current period. */
export function useBudgets(month?: string) {
  const spaceId = useSpaceId()
  return useQuery({
    queryKey: ['money', spaceId, 'budgets', { month }],
    queryFn: () => api<BudgetMonth>(`${base(spaceId!)}/budgets${month ? `?month=${month}` : ''}`),
    enabled: !!spaceId,
  })
}

/** Several budget months at once (a report over a year), sharing the cache with useBudgets. */
export function useBudgetMonths(months: string[]) {
  const spaceId = useSpaceId()
  return useQueries({
    queries: months.map((month) => ({
      queryKey: ['money', spaceId, 'budgets', { month }],
      queryFn: () => api<BudgetMonth>(`${base(spaceId!)}/budgets?month=${month}`),
      enabled: !!spaceId,
    })),
  })
}

/** Sets the limit from `from` onwards; earlier months keep theirs. */
export function useSetBudget() {
  return useMoneyMutation((spaceId, { categoryId, amount, from }: { categoryId: string; amount: number; from: string }) =>
    api<BudgetMonth>(`${base(spaceId)}/budgets/${categoryId}`, { method: 'PUT', json: { amount, from } }))
}

export function useRemoveBudget() {
  return useMoneyMutation((spaceId, { categoryId, from }: { categoryId: string; from: string }) =>
    api<void>(`${base(spaceId)}/budgets/${categoryId}?from=${from}`, { method: 'DELETE' }))
}

/** "2026-10" plus or minus months. */
export function shiftMonth(month: string, by: number): string {
  const [y, m] = month.split('-').map(Number)
  const d = new Date(y!, m! - 1 + by, 1)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

/** BILL: an expense in a category (EMI, rent, insurance). SAVING: a transfer to your own account (SIP, RD). */
export type CommitmentKind = 'BILL' | 'SAVING'

export interface CommitmentFields {
  kind: CommitmentKind
  name: string
  amount: number
  accountId: string
  categoryId: string | null
  toAccountId: string | null
  /** 1 monthly, 3 quarterly, 6 half-yearly, 12 yearly. */
  everyMonths: number
  firstDue: string
  endsOn: string | null
}

export interface Commitment extends CommitmentFields {
  id: string
  currency: string
  version: number
}

export interface Due {
  commitmentId: string
  kind: CommitmentKind
  name: string
  dueDate: string
  /** What was paid, or the usual amount while unpaid. */
  amount: number
  currency: string
  transactionId: string | null
  paid: boolean
}

export interface SafeToSpend {
  month: string
  start: string
  end: string
  daysLeft: number
  currency: string
  /** False when the ledger couldn't be reached: income, spent, safeToSpend and perDay are then null. */
  known: boolean
  income: number | null
  spent: number | null
  billsLeft: number
  savings: number
  /** This month's set-asides for big yearly and one-off costs. */
  setAside: number
  safeToSpend: number | null
  perDay: number | null
}

export function useCommitments() {
  const spaceId = useSpaceId()
  return useQuery({
    queryKey: ['money', spaceId, 'commitments'],
    queryFn: () => api<Commitment[]>(`${base(spaceId!)}/commitments`),
    enabled: !!spaceId,
  })
}

/** Due dates between two dates (yyyy-mm-dd, inclusive), paid or not. */
export function useDue(from: string, to: string) {
  const spaceId = useSpaceId()
  return useQuery({
    queryKey: ['money', spaceId, 'commitments-due', { from, to }],
    queryFn: () => api<Due[]>(`${base(spaceId!)}/commitments/due?${new URLSearchParams({ from, to })}`),
    enabled: !!spaceId,
  })
}

export function useSafeToSpend() {
  const spaceId = useSpaceId()
  return useQuery({
    queryKey: ['money', spaceId, 'safe-to-spend'],
    queryFn: () => api<SafeToSpend>(`${base(spaceId!)}/safe-to-spend`),
    enabled: !!spaceId,
  })
}

export function useCreateCommitment() {
  return useMoneyMutation((spaceId, body: CommitmentFields) =>
    api<Commitment>(`${base(spaceId)}/commitments`, { method: 'POST', json: body }))
}

export function useUpdateCommitment() {
  return useMoneyMutation((spaceId, { id, version, fields }: { id: string; version: number; fields: CommitmentFields }) =>
    api<Commitment>(`${base(spaceId)}/commitments/${id}?version=${version}`, { method: 'PUT', json: fields }))
}

export function useDeleteCommitment() {
  return useMoneyMutation((spaceId, id: string) => api<void>(`${base(spaceId)}/commitments/${id}`, { method: 'DELETE' }))
}

/** Records the payment as an expense (bill) or transfer (saving); 409 already-paid if it was. */
export function usePayCommitment() {
  return useMoneyMutation((spaceId, { id, dueDate, amount }: { id: string; dueDate: string; amount?: number }) =>
    api<Due>(`${base(spaceId)}/commitments/${id}/payments`, { method: 'POST', json: { dueDate, amount } }))
}

/** "Every month", "Every 3 months", "Every year". */
export function everyLabel(months: number): string {
  if (months === 1) return 'Every month'
  if (months === 12) return 'Every year'
  return `Every ${months} months`
}

/** Whole days from today (local) to a yyyy-mm-dd date; negative when it has passed. */
export function daysUntil(iso: string, today = new Date()): number {
  const [y, m, d] = iso.split('-').map(Number)
  const start = new Date(today.getFullYear(), today.getMonth(), today.getDate())
  return Math.round((new Date(y!, m! - 1, d!).getTime() - start.getTime()) / 86_400_000)
}

/** A big yearly or one-off bill and what to set aside for it this month. */
export interface PlannedCost {
  commitmentId: string
  name: string
  amount: number
  currency: string
  categoryId: string | null
  everyMonths: number
  oneOff: boolean
  nextDue: string
  /** Budget months before the one it is due in; 0 when due this month. */
  monthsLeft: number
  paid: boolean
  monthlySetAside: number
  /** Due this month and set aside for in the months before. */
  covered: boolean
}

export interface Planner { month: string; monthlySetAside: number; items: PlannedCost[] }

export function usePlanner() {
  const spaceId = useSpaceId()
  return useQuery({
    queryKey: ['money', spaceId, 'planner'],
    queryFn: () => api<Planner>(`${base(spaceId!)}/planner`),
    enabled: !!spaceId,
  })
}

export type ImportStatus = 'READY' | 'DUPLICATE' | 'ERROR'

export interface ImportProblem { line: number; column: string; reason: string }

export interface ImportRow {
  line: number
  status: ImportStatus
  problems: ImportProblem[]
  kind: TxnKind | null
  amount: number | null
  date: string | null
  account: string
  toAccount: string | null
  category: string | null
  description: string
}

/** A name in the file the space doesn't have; `key` is what a mapping uses. */
export interface ImportUnknown { key: string; name: string; kind: 'ACCOUNT' | 'EXPENSE' | 'INCOME'; parent: string | null; rows: number }

export interface ImportPreview {
  total: number
  ready: number
  duplicates: number
  errors: number
  rows: ImportRow[]
  unknownAccounts: ImportUnknown[]
  unknownCategories: ImportUnknown[]
}

/**
 * accounts: key -> account id or `new:BANK` (CASH, CARD, WALLET); categories: key -> category id or `new`.
 */
export interface ImportRequest {
  fileName: string
  /** The file's text, for a CSV, or */
  csv?: string
  /** an Excel workbook, base64-encoded. */
  xlsx?: string
  accounts: Record<string, string>
  categories: Record<string, string>
}

export interface ImportBatch { id: string; fileName: string; imported: number; duplicates: number; skipped: number; createdAt: string; undone: boolean }

/** Checks every row; saves nothing. */
export function usePreviewImport() {
  const spaceId = useSpaceId()
  return useMutation({
    mutationFn: (body: ImportRequest) => api<ImportPreview>(`${base(spaceId!)}/imports/preview`, { method: 'POST', json: body }),
  })
}

export function useCommitImport() {
  return useMoneyMutation((spaceId, body: ImportRequest) =>
    api<{ importId: string; imported: number; duplicates: number; skipped: number }>(`${base(spaceId)}/imports`, { method: 'POST', json: body }))
}

export function useImports() {
  const spaceId = useSpaceId()
  return useQuery({
    queryKey: ['money', spaceId, 'imports'],
    queryFn: () => api<ImportBatch[]>(`${base(spaceId!)}/imports`),
    enabled: !!spaceId,
  })
}

export function useUndoImport() {
  return useMoneyMutation((spaceId, id: string) => api<void>(`${base(spaceId)}/imports/${id}`, { method: 'DELETE' }))
}

/** Where the template downloads from (same origin, so the session cookie goes with it). */
export function templateUrl(spaceId: string, format: 'csv' | 'xlsx' = 'csv'): string {
  return `${base(spaceId)}/imports/template.${format}`
}

/** A file's bytes as base64, for sending an Excel workbook inside JSON. */
export async function base64Of(file: Blob): Promise<string> {
  const bytes = new Uint8Array(await file.arrayBuffer())
  let binary = ''
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000))
  return btoa(binary)
}

/** The preview's problems as a CSV the user can open next to their file. */
export function problemsCsv(rows: ImportRow[]): string {
  const cell = (v: string | number) => (/[",\n]/.test(String(v)) ? `"${String(v).replace(/"/g, '""')}"` : String(v))
  const lines = rows.flatMap((r) => r.problems.map((p) => [p.line, p.column, p.reason].map(cell).join(',')))
  return ['row,column,reason', ...lines].join('\r\n') + '\r\n'
}

/** Transactions between two dates as a file in the import template's columns (importing it back adds nothing). */
export function exportUrl(spaceId: string, format: 'csv' | 'xlsx', from: string, to: string): string {
  return `${base(spaceId)}/exports/transactions.${format}?${new URLSearchParams({ from, to })}`
}
