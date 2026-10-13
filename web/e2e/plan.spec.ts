import { expect, test } from '@playwright/test'
import { signIn } from './helpers'

test.beforeEach(async ({ page }) => {
  await signIn(page)
})

test('a bill on the plan is due, can be marked paid once, and paying it records an expense', async ({ page }) => {
  // The demo user is shared by every run: this run's own names keep it apart, and it cleans up after itself.
  const run = Date.now()
  const bank = `E2E plan bank ${run}`
  const bill = `E2E Netflix ${run}`

  await page.goto('/accounts')
  await page.getByRole('button', { name: 'Add account' }).first().click()
  let dialog = page.getByRole('dialog', { name: 'Add an account' })
  await dialog.getByLabel('Name').fill(bank)
  await dialog.getByRole('button', { name: 'Add account' }).click()
  await expect(dialog).toBeHidden()

  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Plan' }).click()
  await expect(page.getByText('Safe to spend').first()).toBeVisible()
  await page.getByRole('button', { name: 'Add bill or saving' }).first().click()
  dialog = page.getByRole('dialog', { name: 'Add to your plan' })
  await dialog.getByLabel('Name').fill(bill)
  await dialog.getByLabel('Amount').fill('649')
  await dialog.getByLabel('Paid from').selectOption({ label: bank })
  await dialog.getByLabel('Category').selectOption({ label: 'Subscriptions' })
  // "Next due" defaults to today.
  await dialog.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(dialog).toBeHidden()

  await expect(page.getByRole('button', { name: `Edit ${bill}` })).toContainText('Every month')
  const due = page.getByTestId(`due-${bill}`)
  await expect(due).toContainText('Due today')
  await expect(due).toContainText('₹649')
  await due.getByRole('button', { name: 'Mark paid' }).click()
  await expect(due.getByText('Paid', { exact: true }).first()).toBeVisible()
  await expect(due.getByRole('button', { name: 'Mark paid' })).toBeHidden()

  // The payment is an ordinary expense from that account.
  await page.goto('/transactions')
  await page.getByLabel('Filter by account').selectOption({ label: bank })
  const row = page.getByRole('button', { name: `Edit ${bill}` })
  await expect(row).toContainText('-₹649.00')
  await expect(row).toContainText(`Subscriptions · ${bank}`)

  // Deleting the payment makes it due again.
  await row.click()
  await page.getByRole('dialog', { name: 'Edit expense' }).getByRole('button', { name: 'Delete' }).click()
  await page.goto('/plan')
  await expect(page.getByTestId(`due-${bill}`).getByRole('button', { name: 'Mark paid' })).toBeVisible()

  // Clean up.
  await page.getByRole('button', { name: `Edit ${bill}` }).click()
  await page.getByRole('dialog', { name: `Edit ${bill}` }).getByRole('button', { name: 'Remove' }).click()
  await expect(page.getByRole('button', { name: `Edit ${bill}` })).toBeHidden()
  await page.goto('/accounts')
  await page.getByRole('button', { name: `Edit ${bank}` }).click()
  await page.getByRole('dialog', { name: `Edit ${bank}` }).getByRole('button', { name: 'Archive' }).click()
  await expect(page.getByRole('button', { name: `Edit ${bank}` })).toBeHidden()
})
