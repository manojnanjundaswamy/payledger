import { expect, test } from '@playwright/test'
import { signIn } from './helpers'

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
  await add.getByLabel('Category').selectOption({ label: category })
  await add.getByLabel('Paid from').selectOption({ label: bank })
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

  // A category opens its transactions for the same dates.
  await row.click()
  await expect(page).toHaveURL(/\/transactions\?.*category=/)
  await expect(page.getByTestId('filter-chip')).toContainText(category)
  await expect(page.getByRole('button', { name: 'Edit Report check' })).toBeVisible()
  await page.getByRole('button', { name: 'Clear filter' }).click()
  await expect(page.getByTestId('filter-chip')).toBeHidden()

  // Custom dates.
  await page.goto('/reports?period=custom&from=2026-01-01&to=2026-01-31')
  await expect(page.getByTestId('report-range')).toHaveText('1 Jan 2026 – 31 Jan 2026')

  // Clean up.
  await page.goto('/transactions')
  await page.getByLabel('Filter by account').selectOption({ label: bank })
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
