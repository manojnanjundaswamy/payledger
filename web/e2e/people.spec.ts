import { expect, test } from '@playwright/test'
import { signIn } from './helpers'

test('money lent to someone new shows on People as owed to you, with its date, and in a one-to-one ledger', async ({ page }) => {
  await signIn(page)
  const name = `E2E Kiran ${Date.now()}`
  const due = new Date(Date.now() + 10 * 86_400_000)
  const dueIso = `${due.getFullYear()}-${String(due.getMonth() + 1).padStart(2, '0')}-${String(due.getDate()).padStart(2, '0')}`

  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'People' }).click()
  await page.getByRole('button', { name: 'I lent money' }).click()
  const dialog = page.getByRole('dialog', { name: 'Money you lent' })
  await dialog.getByLabel('To', { exact: true }).click()
  await page.getByRole('textbox', { name: 'Search the list' }).fill(name)
  await page.getByRole('option', { name: `New person "${name}"` }).click()
  await dialog.getByLabel('Amount').fill('2500')
  await dialog.getByLabel('Pay back by (optional)').fill(dueIso)
  await dialog.getByRole('button', { name: 'Save' }).click()
  await expect(dialog).toBeHidden()

  const row = page.getByTestId(`person-${name}`)
  await expect(row).toContainText('₹2,500')
  await expect(row).toContainText('owes you')
  await expect(row).toContainText('due')
  await row.getByRole('button').first().click()
  await row.getByRole('link', { name: /Loans between you/ }).click()
  await expect(page.getByRole('button', { name: `Edit Lent to ${name}` })).toContainText('Loan')
})
