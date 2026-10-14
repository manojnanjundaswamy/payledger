import { expect, test } from '@playwright/test'
import { signIn, pick } from './helpers'

test.beforeEach(async ({ page }) => {
  await signIn(page)
})

test('an account and an expense end up in the ledger, and edits and deletes change the balance', async ({ page }) => {
  // The demo user is shared by every run: a unique name keeps this run's account apart.
  const name = `E2E bank ${Date.now()}`
  const balance = page.getByTestId(`balance-${name}`)

  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Accounts' }).click()
  await page.getByRole('button', { name: 'Add account' }).first().click()
  const accountDialog = page.getByRole('dialog', { name: 'Add an account' })
  await accountDialog.getByText('Bank account').click()
  await accountDialog.getByLabel('Name').fill(name)
  await accountDialog.getByLabel('Balance').fill('50,000')
  await accountDialog.getByRole('button', { name: 'Add account' }).click()
  // The ledger applies the opening balance asynchronously; the page refetches until it shows.
  await expect(balance).toHaveText('₹50,000.00', { timeout: 15_000 })
  // Net worth month by month, from the ledger.
  await expect(page.getByRole('figure', { name: 'Net worth over the last 12 months' })).toContainText('in 12 months')

  // The sidebar's Add menu opens the dialog straight away; amount first.
  await page.getByRole('button', { name: 'Add', exact: true }).first().click()
  await page.getByRole('menuitem', { name: 'Expense' }).click()
  const add = page.getByRole('dialog', { name: 'Add expense' })
  await expect(add.getByLabel('Amount')).toBeFocused()
  // Something that isn't an amount is caught before anything is sent.
  await add.getByLabel('Amount').fill('12abc')
  await add.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(add.getByRole('alert')).toHaveText('Enter an amount, like 486 or 1,200.50.')
  await add.getByLabel('Amount').fill('486')
  await pick(add, 'Category', 'Groceries')
  await pick(add, 'Paid from', name)
  await add.getByLabel('Description').fill('BigBasket')
  await add.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(add).toBeHidden()

  await pick(page, 'Filter by account', name)
  const row = page.getByRole('button', { name: 'Edit BigBasket' })
  await expect(row).toContainText('-₹486.00')
  await expect(row).toContainText(`Groceries · ${name}`)

  // Edit the amount: the ledger reverses the old version and posts the new one.
  await row.click()
  const edit = page.getByRole('dialog', { name: 'Edit expense' })
  await expect(edit.getByLabel('Amount')).toHaveValue('486')
  await edit.getByLabel('Amount').fill('1,200.50')
  await edit.getByRole('button', { name: 'Save' }).click()
  await expect(row).toContainText('-₹1,200.50')

  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Accounts' }).click()
  await expect(balance).toHaveText('₹48,799.50', { timeout: 15_000 })

  // Delete it: back to the opening balance.
  await page.goto('/transactions')
  await pick(page, 'Filter by account', name)
  await page.getByRole('button', { name: 'Edit BigBasket' }).click()
  await page.getByRole('dialog', { name: 'Edit expense' }).getByRole('button', { name: 'Delete' }).click()
  await expect(page.getByText('Nothing here yet')).toBeVisible()

  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Accounts' }).click()
  await expect(balance).toHaveText('₹50,000.00', { timeout: 15_000 })

  // Tidy up: archive the account so the demo user's list doesn't grow with every run.
  await page.getByRole('button', { name: `Edit ${name}` }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Archive' }).click()
  await expect(page.getByRole('button', { name: `Edit ${name}` })).toBeHidden()
})
