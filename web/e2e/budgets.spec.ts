import { expect, test } from '@playwright/test'
import { signIn } from './helpers'

test.beforeEach(async ({ page }) => {
  await signIn(page)
})

test('a budget shows spending from the ledger, its status, and only from its month on', async ({ page }) => {
  // The demo user is shared by every run: this run's own category and account keep its numbers apart.
  const run = Date.now()
  const food = `E2E food ${run}`
  const bank = `E2E budget bank ${run}`

  await page.goto('/categories')
  await page.getByRole('button', { name: 'Add category' }).click()
  let dialog = page.getByRole('dialog', { name: 'Add a spending category' })
  await dialog.getByLabel('Name').fill(food)
  await dialog.getByRole('button', { name: 'Add category' }).click()
  await expect(dialog).toBeHidden()

  await page.goto('/accounts')
  await page.getByRole('button', { name: 'Add account' }).first().click()
  dialog = page.getByRole('dialog', { name: 'Add an account' })
  await dialog.getByLabel('Name').fill(bank)
  await dialog.getByRole('button', { name: 'Add account' }).click()
  await expect(dialog).toBeHidden()

  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Budgets' }).click()
  const thisMonth = await page.getByTestId('budget-month').textContent()
  await page.getByRole('button', { name: 'Set budget' }).first().click()
  dialog = page.getByRole('dialog', { name: 'Set a budget' })
  await dialog.getByLabel('Category').selectOption({ label: food })
  await dialog.getByLabel('Monthly limit').fill('1,000')
  await dialog.getByRole('switch', { name: 'Roll over' }).click()
  await dialog.getByRole('button', { name: 'Set budget' }).click()
  await expect(dialog).toBeHidden()
  const card = page.getByTestId(`budget-card-${food}`)
  const edit = page.getByRole('button', { name: `Edit ${food} budget` })
  await expect(card).toContainText('₹0 of ₹1,000')
  await expect(card).toContainText('On track')

  // ₹900 of ₹1,000 spent: close to the limit.
  await page.goto('/transactions?add=EXPENSE')
  const add = page.getByRole('dialog', { name: 'Add expense' })
  await add.getByLabel('Amount').fill('900')
  await add.getByLabel('Category').selectOption({ label: food })
  await add.getByLabel('Paid from').selectOption({ label: bank })
  await add.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(add).toBeHidden()

  await page.goto('/budgets')
  // The ledger applies the expense a moment later.
  await expect(async () => {
    await page.reload()
    await expect(card).toContainText('₹900 of ₹1,000', { timeout: 1_000 })
  }).toPass({ timeout: 15_000 })
  await expect(card).toContainText('Close')
  await expect(card).toContainText('₹100 left')
  // Rollover stays on for the next edit.
  await edit.click()
  await expect(page.getByRole('dialog', { name: `${food} budget` }).getByRole('switch', { name: 'Roll over' })).toBeChecked()
  await page.getByRole('dialog', { name: `${food} budget` }).getByRole('button', { name: 'Cancel' }).click()

  // The month before has no budget for it.
  await page.getByRole('button', { name: 'Previous month' }).click()
  await expect(page.getByTestId('budget-month')).not.toHaveText(thisMonth!)
  await expect(page.getByRole('button', { name: `Edit ${food} budget` })).toBeHidden()
  await page.getByRole('button', { name: 'This month' }).click()
  await expect(page.getByTestId('budget-month')).toHaveText(thisMonth!)

  // Clean up: stop the budget, delete the expense, hide the category, archive the account.
  await edit.click()
  await page.getByRole('dialog', { name: `${food} budget` }).getByRole('button', { name: 'Stop budgeting' }).click()
  await expect(card).toBeHidden()

  await page.goto('/transactions')
  await page.getByLabel('Filter by account').selectOption({ label: bank })
  await page.getByRole('button', { name: `Edit ${food}` }).click()
  await page.getByRole('dialog', { name: 'Edit expense' }).getByRole('button', { name: 'Delete' }).click()
  await expect(page.getByText('Nothing here yet')).toBeVisible()

  await page.goto('/categories')
  await page.getByRole('button', { name: `Edit ${food}` }).click()
  await page.getByRole('dialog', { name: `Edit ${food}` }).getByRole('button', { name: 'Hide' }).click()
  await expect(page.getByRole('region', { name: 'Hidden' }).getByRole('button', { name: `Edit ${food}` })).toBeVisible()

  await page.goto('/accounts')
  await page.getByRole('button', { name: `Edit ${bank}` }).click()
  await page.getByRole('dialog', { name: `Edit ${bank}` }).getByRole('button', { name: 'Archive' }).click()
  await expect(page.getByRole('button', { name: `Edit ${bank}` })).toBeHidden()
})
