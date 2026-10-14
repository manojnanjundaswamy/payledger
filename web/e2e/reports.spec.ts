import { readFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'
import { callApi, signIn, pick } from './helpers'

test.beforeEach(async ({ page }) => {
  await signIn(page)
})

test('reports show spending by category for the financial year and open its transactions', async ({ page }) => {
  // The demo user is shared by every run: this run's own category, account and expense keep it apart.
  const run = Date.now()
  const category = `E2E report ${run}`
  const bank = `E2E report bank ${run}`

  await page.goto('/categories')
  await page.getByRole('button', { name: 'Add category' }).click()
  let dialog = page.getByRole('dialog', { name: 'Add a spending category' })
  await dialog.getByLabel('Name').fill(category)
  await dialog.getByRole('button', { name: 'Add category' }).click()
  await expect(dialog).toBeHidden()
  await page.goto('/accounts')
  await page.getByRole('button', { name: 'Add account' }).first().click()
  dialog = page.getByRole('dialog', { name: 'Add an account' })
  await dialog.getByLabel('Name').fill(bank)
  await dialog.getByRole('button', { name: 'Add account' }).click()
  await expect(dialog).toBeHidden()
  await page.goto('/transactions?add=EXPENSE')
  const add = page.getByRole('dialog', { name: 'Add expense' })
  await add.getByLabel('Amount').fill('321')
  await pick(add, 'Category', category)
  await pick(add, 'Paid from', bank)
  await add.getByLabel('Description').fill('Report check')
  await add.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(add).toBeHidden()

  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Reports' }).click()
  await page.getByLabel('Period').selectOption({ label: 'This financial year' })
  await expect(page.getByTestId('report-range')).toContainText(/FY \d{4}–\d{2} · 1 Apr \d{4} – 31 Mar \d{4}/)
  // The ledger applies the expense a moment later.
  const row = page.getByRole('link', { name: new RegExp(`^${category}: ₹321, see transactions`) })
  await expect(async () => {
    await page.reload()
    await expect(row).toBeVisible({ timeout: 1_000 })
  }).toPass({ timeout: 15_000 })
  await expect(page.getByRole('region', { name: 'Month by month' })).toBeVisible()

  // The period's transactions download in the template's columns.
  const download = page.waitForEvent('download')
  await page.getByRole('link', { name: 'CSV' }).click()
  const file = await download
  expect(file.suggestedFilename()).toMatch(/^payledger-transactions-\d{4}-04-01-to-\d{4}-03-31\.csv$/)
  expect(readFileSync(await file.path(), 'utf-8')).toContain(`,expense,${bank},${category},,Report check,`)

  // A category opens its transactions for the same dates.
  await row.click()
  await expect(page).toHaveURL(/\/transactions\?.*category=/)
  await expect(page.getByTestId('filter-chip').filter({ hasText: category })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Edit Report check' })).toBeVisible()
  await page.getByRole('button', { name: `Remove filter ${category}` }).click()
  await expect(page.getByTestId('filter-chip').filter({ hasText: category })).toBeHidden()
  await page.getByRole('button', { name: 'Clear all' }).click()
  await expect(page.getByTestId('filter-chip')).toHaveCount(0)

  // Custom dates.
  await page.goto('/reports?period=custom&from=2026-01-01&to=2026-01-31')
  await expect(page.getByTestId('report-range')).toHaveText('1 Jan 2026 – 31 Jan 2026')

  // Clean up.
  await page.goto('/transactions')
  await pick(page, 'Filter by account', bank)
  await page.getByRole('button', { name: 'Edit Report check' }).click()
  await page.getByRole('dialog', { name: 'Edit expense' }).getByRole('button', { name: 'Delete' }).click()
  await expect(page.getByText('Nothing here yet')).toBeVisible()
  await page.goto('/categories')
  await page.getByRole('button', { name: `Edit ${category}` }).click()
  await page.getByRole('dialog', { name: `Edit ${category}` }).getByRole('button', { name: 'Hide' }).click()
  await page.goto('/accounts')
  await page.getByRole('button', { name: `Edit ${bank}` }).click()
  await page.getByRole('dialog', { name: `Edit ${bank}` }).getByRole('button', { name: 'Archive' }).click()
  await expect(page.getByRole('button', { name: `Edit ${bank}` })).toBeHidden()
})

test('a report narrows by account, breaks spending down by place, day and account, and downloads a summary', async ({ page }) => {
  const run = Date.now()
  const me = await callApi<{ spaces: { id: string }[] }>(page, 'GET', '/api/core/me')
  const base = `/api/core/spaces/${me.spaces[0]!.id}`
  const categories = await callApi<{ id: string; name: string }[]>(page, 'GET', `${base}/categories`)
  const account = await callApi<{ id: string }>(page, 'POST', `${base}/accounts`, { kind: 'WALLET', name: `E2E rpt ${run}`, openingBalance: 0, openingDate: '2026-01-01' })
  const now = new Date()
  const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
  const made: { id: string; version: number }[] = []
  for (const [d, a] of [[`E2E Chai ${run}`, 4_000], [`E2E Chai ${run}`, 6_000], [`E2E Books ${run}`, 50_000]] as const) {
    made.push(await callApi(page, 'POST', `${base}/transactions`, { kind: 'EXPENSE', amount: a, accountId: account.id, categoryId: categories.find((c) => c.name === 'Eating out')!.id, occurredOn: today, description: d, tags: [`rpt${run}`] }))
  }

  await page.goto(`/reports?period=custom&from=${today.slice(0, 8)}01&to=${today}&account=${account.id}`)
  await expect(page.getByTestId('report-spending')).toHaveText('₹600')
  await expect(page.getByTestId('report-range')).toContainText('narrowed by the filters')
  // Places: the two chai payments are one place.
  const places = page.getByRole('region', { name: 'Top places' })
  await expect(places).toContainText(`E2E Chai ${run} · 2×`)
  // Day by day: today carries the spending; it opens that day's transactions.
  await expect(page.getByTestId(`day-${today}`)).toContainText('₹600')
  await expect(page.getByRole('region', { name: 'By tag' })).toContainText(`#rpt${run}`)

  // The summary downloads as one CSV.
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Download the summary' }).click()
  const csv = readFileSync(await (await download).path(), 'utf-8')
  expect(csv).toContain('Spending,6.00'.replace('6.00', '600.00'))
  expect(csv).toContain(`E2E Chai ${run},100.00,2`)

  // A place opens its transactions, with the report's filters kept.
  await places.getByRole('button', { name: new RegExp(`E2E Books ${run}`) }).click()
  await expect(page).toHaveURL(/\/transactions\?.*account=/)
  await expect(page.getByRole('button', { name: `Edit E2E Books ${run}` })).toBeVisible()
  await expect(page.getByRole('button', { name: `Edit E2E Chai ${run}` })).toHaveCount(0)

  for (const t of made) await callApi(page, 'DELETE', `${base}/transactions/${t.id}?version=${t.version}`)
  await callApi(page, 'PATCH', `${base}/accounts/${account.id}`, { archived: true })
})
