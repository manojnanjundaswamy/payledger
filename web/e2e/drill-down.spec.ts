import { expect, test } from '@playwright/test'
import { signIn } from './helpers'

test.beforeEach(async ({ page }) => {
  await signIn(page)
})

// Every number leads to what's behind it. Uses only this run's own account and category (the demo user holds real data).
test('accounts, categories, budgets, dashboard rows and charts open what is behind them', async ({ page }) => {
  const run = Date.now()
  const bank = `E2E drill bank ${run}`
  const cat = `E2E drill ${run}`
  const what = `E2E drill lunch ${run}`

  await page.goto('/categories')
  await page.getByRole('button', { name: 'Add category' }).click()
  let dialog = page.getByRole('dialog', { name: 'Add a spending category' })
  await dialog.getByLabel('Name').fill(cat)
  await dialog.getByRole('button', { name: 'Add category' }).click()
  await expect(dialog).toBeHidden()

  await page.goto('/accounts')
  await page.getByRole('button', { name: 'Add account' }).first().click()
  dialog = page.getByRole('dialog', { name: 'Add an account' })
  await dialog.getByLabel('Name').fill(bank)
  await dialog.getByRole('button', { name: 'Add account' }).click()
  await expect(dialog).toBeHidden()

  await page.goto('/transactions?add=EXPENSE')
  dialog = page.getByRole('dialog', { name: 'Add expense' })
  await dialog.getByLabel('Amount').fill('321')
  await dialog.getByLabel('Category').selectOption({ label: cat })
  await dialog.getByLabel('Paid from').selectOption({ label: bank })
  await dialog.getByLabel('Description').fill(what)
  await dialog.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(dialog).toBeHidden()

  // An account opens its transactions; the pencil still edits it.
  await page.goto('/accounts')
  await page.getByRole('link', { name: `${bank} transactions` }).click()
  await expect(page).toHaveURL(/\/transactions\?account=/)
  await expect(page.getByLabel('Filter by account')).toHaveValue(/.+/)
  await expect(page.getByRole('button', { name: `Edit ${what}` })).toBeVisible()

  // The transaction links to everything related.
  await page.getByRole('button', { name: `Edit ${what}` }).click()
  dialog = page.getByRole('dialog', { name: 'Edit expense' })
  await dialog.getByRole('navigation', { name: 'Related' }).getByRole('link', { name: `All ${cat}` }).click()
  await expect(page).toHaveURL(/category=/)
  await expect(page.getByTestId('filter-chip')).toContainText(cat)

  // A category opens its transactions.
  await page.goto('/categories')
  await page.getByRole('link', { name: `${cat} transactions` }).click()
  await expect(page.getByRole('button', { name: `Edit ${what}` })).toBeVisible()

  // Recent transactions on the dashboard open the transaction itself.
  await page.goto('/dashboard')
  const recent = page.getByRole('link', { name: `Open ${what}` })
  await expect(recent).toBeVisible()
  await recent.click()
  await expect(page.getByRole('dialog', { name: 'Edit expense' }).getByLabel('Description')).toHaveValue(what)
  await page.keyboard.press('Escape')
  await expect(page).not.toHaveURL(/open=/)

  // A bar in "Spending by category" opens that category's spending this month (the ledger posts a moment later).
  await expect(async () => {
    await page.goto('/dashboard')
    await expect(page.getByRole('button', { name: new RegExp(`^${cat}: `) })).toBeVisible({ timeout: 1_000 })
  }).toPass({ timeout: 15_000 })
  await page.getByRole('button', { name: new RegExp(`^${cat}: `) }).click()
  await expect(page).toHaveURL(/category=.*from=/)
  await expect(page.getByRole('button', { name: `Edit ${what}` })).toBeVisible()

  // ?open= works on its own too (a shared link).
  await page.goto('/transactions')
  await page.getByLabel('Filter by account').selectOption({ label: bank })
  await page.getByRole('button', { name: `Edit ${what}` }).click()
  await page.keyboard.press('Escape')

  // Reports: a month column zooms into that month.
  await page.goto('/reports?period=last-12')
  const column = page.getByRole('button', { name: /Income .*Spending/ }).last()
  await column.click()
  await expect(page).toHaveURL(/period=custom/)

  // Clean up: delete the expense, archive the account, hide the category.
  await page.goto('/transactions')
  await page.getByLabel('Filter by account').selectOption({ label: bank })
  await page.getByRole('button', { name: `Edit ${what}` }).click()
  await page.getByRole('dialog', { name: 'Edit expense' }).getByRole('button', { name: 'Delete' }).click()
  await expect(page.getByText('Nothing here yet')).toBeVisible()
  await page.goto('/accounts')
  await page.getByRole('button', { name: `Edit ${bank}` }).click()
  await page.getByRole('dialog', { name: `Edit ${bank}` }).getByRole('button', { name: 'Archive' }).click()
  await page.goto('/categories')
  await page.getByRole('button', { name: `Edit ${cat}` }).click()
  await page.getByRole('dialog', { name: `Edit ${cat}` }).getByRole('button', { name: 'Hide' }).click()
})

test('the admin dashboard numbers open the users behind them', async ({ page }) => {
  await page.goto('/admin')
  await page.getByTestId('kpi-premium').click()
  await expect(page).toHaveURL(/tab=users.*show=PREMIUM|show=PREMIUM.*tab=users/)
  await expect(page.getByRole('radio', { name: 'Premium' })).toBeChecked()
  // The demo user has complimentary Premium.
  await expect(page.getByTestId('users-total')).not.toHaveText('0 users')
})
