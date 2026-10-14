import { test } from '@playwright/test'
import { callApi, signIn } from './helpers'

/**
 * Not a test: scripts/clean-test-data.sh runs it (CLEANUP=1) to take the browser tests' "E2E …" leftovers out of the
 * demo account through the API, so the ledger reverses them and balances, budgets and the dashboard stay right. The
 * script then removes the rows themselves, the test users and the test groups.
 */
test('remove the E2E leftovers from the demo account', async ({ page }) => {
  test.setTimeout(600_000)
  await signIn(page)
  const me = await callApi<{ spaces: { id: string }[] }>(page, 'GET', '/api/core/me')
  const base = `/api/core/spaces/${me.spaces[0]!.id}`
  const isTest = (name: string | null | undefined) => !!name && /^E2E\b/i.test(name.trim())
  // The test groups' expenses and payments booked in your budget end with the group's name: "Dinner · E2E trip".
  const isTestBooking = (description: string) => / · E2E\b[^·]*$/i.test(description)

  const accounts = await callApi<{ id: string; name: string; openingBalance: number }[]>(page, 'GET', `${base}/accounts?includeArchived=true`)
  const categories = await callApi<{ id: string; name: string }[]>(page, 'GET', `${base}/categories`)
  const testAccounts = new Set(accounts.filter((a) => isTest(a.name)).map((a) => a.id))
  const testCategories = new Set(categories.filter((c) => isTest(c.name)).map((c) => c.id))

  // Transactions: E2E-named ones, and anything in a test account or category.
  let removed = 0
  for (;;) {
    let cursor: string | null = null
    const doomed: { id: string; version: number }[] = []
    do {
      const page1: { items: { id: string; version: number; description: string; accountId: string; toAccountId: string | null; categoryId: string | null }[]; nextCursor: string | null } =
        await callApi(page, 'GET', `${base}/transactions?limit=200${cursor ? `&cursor=${cursor}` : ''}`)
      doomed.push(...page1.items.filter((t) => isTest(t.description) || isTestBooking(t.description) || testAccounts.has(t.accountId) || (t.toAccountId && testAccounts.has(t.toAccountId))
        || (t.categoryId && testCategories.has(t.categoryId))))
      cursor = page1.nextCursor
    } while (cursor)
    if (doomed.length === 0) break
    for (const t of doomed) await callApi(page, 'DELETE', `${base}/transactions/${t.id}?version=${t.version}`)
    removed += doomed.length
  }

  // Bills and savings, then the accounts' opening balances (the ledger reverses each).
  const commitments = await callApi<{ id: string; name: string; accountId: string; categoryId: string | null }[]>(page, 'GET', `${base}/commitments`)
  for (const c of commitments.filter((c) => isTest(c.name) || testAccounts.has(c.accountId) || (c.categoryId && testCategories.has(c.categoryId)))) {
    await callApi(page, 'DELETE', `${base}/commitments/${c.id}`)
  }
  for (const a of accounts.filter((a) => testAccounts.has(a.id) && a.openingBalance !== 0)) {
    await callApi(page, 'PATCH', `${base}/accounts/${a.id}`, { openingBalance: 0 })
  }
  console.log(`cleanup: ${removed} transactions, ${testAccounts.size} accounts, ${testCategories.size} categories in the demo account`)
})
