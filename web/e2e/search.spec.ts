import { expect, test } from '@playwright/test'
import { callApi, signIn } from './helpers'

// Search and filters on the demo user's transactions, using only this run's own uniquely named items, removed at the end.
test('transactions can be found by words, amount, type and dates, with totals and an export of exactly that', async ({ page }) => {
  await signIn(page)
  const run = Date.now()
  const me = await callApi<{ spaces: { id: string }[] }>(page, 'GET', '/api/core/me')
  const base = `/api/core/spaces/${me.spaces[0]!.id}`
  const categories = await callApi<{ id: string; name: string }[]>(page, 'GET', `${base}/categories`)
  const eatingOut = categories.find((c) => c.name === 'Eating out')!.id
  const groceries = categories.find((c) => c.name === 'Groceries')!.id
  const account = await callApi<{ id: string }>(page, 'POST', `${base}/accounts`, { kind: 'BANK', name: `E2E search ${run}`, openingBalance: 0, openingDate: '2026-01-01' })
  const today = new Date().toISOString().slice(0, 10)
  const add = (description: string, amount: number, categoryId: string) => callApi<{ id: string; version: number }>(page, 'POST', `${base}/transactions`,
    { kind: 'EXPENSE', amount, accountId: account.id, categoryId, occurredOn: today, description, tags: [`e2e${run}`] })
  const made = [await add(`E2E Zomato ${run}`, 45_000, eatingOut), await add(`E2E BigBasket ${run}`, 250_000, groceries)]

  await page.goto('/transactions')
  const search = page.getByTestId('txn-search')
  await search.fill(String(run))
  await expect(page).toHaveURL(new RegExp(`q=.*${run}`))
  await expect(page.getByRole('button', { name: /^Edit E2E/ })).toHaveCount(2)
  await expect(page.getByTestId('txn-found')).toContainText('2 found')
  await expect(page.getByTestId('txn-found')).toContainText('₹2,950.00')

  // Words find one; so does the category's name.
  await search.fill(`zomato ${run}`)
  await expect(page.getByRole('button', { name: /^Edit E2E/ })).toHaveCount(1)
  await expect(page.getByRole('button', { name: `Edit E2E Zomato ${run}` })).toBeVisible()

  // An amount range, in the filter panel; the chip removes it.
  await search.fill(String(run))
  await page.getByRole('button', { name: 'Filters' }).click()
  const panel = page.getByRole('group', { name: 'Filters' })
  await panel.getByLabel('Amount from (₹)').fill('1,000')
  await panel.getByLabel('Amount from (₹)').press('Enter')
  await expect(page.getByRole('button', { name: /^Edit E2E/ })).toHaveCount(1)
  await expect(page.getByRole('button', { name: `Edit E2E BigBasket ${run}` })).toBeVisible()
  await expect(page.getByTestId('filter-chip').filter({ hasText: '₹1,000 or more' })).toBeVisible()

  // The export is exactly what's shown.
  const csv = await page.getByRole('link', { name: 'Download these as CSV' }).getAttribute('href')
  expect(csv).toContain(`q=${run}`)
  expect(csv).toContain('minAmount=100000')

  await page.getByRole('button', { name: 'Remove filter ₹1,000 or more' }).click()
  await expect(page.getByRole('button', { name: /^Edit E2E/ })).toHaveCount(2)

  // Type: income finds none of them, and says so.
  await panel.getByRole('radio', { name: 'Income' }).click()
  await expect(page.getByText('No transactions match')).toBeVisible()
  await page.getByRole('button', { name: 'Clear filters' }).click()
  await expect(search).toHaveValue('')

  // A tag, straight from a link.
  await page.goto(`/transactions?tag=e2e${run}`)
  await expect(page.getByRole('button', { name: /^Edit E2E/ })).toHaveCount(2)

  for (const t of made) await callApi(page, 'DELETE', `${base}/transactions/${t.id}?version=${t.version}`)
  await callApi(page, 'PATCH', `${base}/accounts/${account.id}`, { archived: true })
})

test('categories can be searched', async ({ page }) => {
  await signIn(page)
  await page.goto('/categories')
  await page.getByLabel('Search categories').fill('grocer')
  await expect(page.getByRole('link', { name: 'Groceries transactions' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Rent transactions' })).toBeHidden()
  await page.getByLabel('Search categories').fill('zzzz')
  await expect(page.getByText('No spending category matches "zzzz".')).toBeVisible()
})
