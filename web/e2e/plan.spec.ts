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

  // The calendar shows it on today's date, paid.
  await page.getByRole('radio', { name: 'Calendar' }).click()
  const now = new Date()
  const todayIso = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
  await expect(page.getByTestId(`day-${todayIso}`)).toContainText(`✓ ${bill}`)
  await page.getByRole('radio', { name: 'List' }).click()

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

test('a yearly cost and a one-off cost are spread into a monthly set-aside', async ({ page }) => {
  const run = Date.now()
  const premium = `E2E insurance ${run}`
  const wedding = `E2E wedding ${run}`
  const inMonths = (n: number) => {
    const d = new Date()
    d.setMonth(d.getMonth() + n)
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  }

  await page.goto('/plan')
  for (const [name, amount, often, due] of [[premium, '18,000', 'Every year', inMonths(5)], [wedding, '50,000', 'Only once', inMonths(8)]]) {
    await page.getByRole('button', { name: 'Add bill or saving' }).first().click()
    const dialog = page.getByRole('dialog', { name: 'Add to your plan' })
    await dialog.getByLabel('Name').fill(name!)
    await dialog.getByLabel('Amount').fill(amount!)
    await dialog.getByLabel('Category').selectOption({ label: 'Insurance' })
    await dialog.getByLabel('How often').selectOption({ label: often! })
    await dialog.getByLabel(often === 'Only once' ? 'Due on' : 'Next due').fill(due!)
    if (often === 'Only once') await expect(dialog.getByLabel('Last payment')).toBeHidden()
    await dialog.getByRole('button', { name: 'Add', exact: true }).click()
    await expect(dialog).toBeHidden()
  }

  const big = page.getByRole('region', { name: 'Big costs ahead' })
  await expect(big.getByTestId(`planned-${premium}`)).toContainText(/₹18,000 every year · in \d months?/)
  await expect(big.getByTestId(`planned-${premium}`)).toContainText('a month')
  await expect(big.getByTestId(`planned-${wedding}`)).toContainText(/₹50,000 once · in \d months?/)
  await expect(page.getByRole('button', { name: `Edit ${wedding}` })).toContainText('Once, on')
  await expect(page.getByTestId('set-aside-total')).toContainText('this month')
  await expect(page.getByText('Set aside for big costs')).toBeVisible()

  for (const name of [premium, wedding]) {
    await page.getByRole('button', { name: `Edit ${name}` }).click()
    const dialog = page.getByRole('dialog', { name: `Edit ${name}` })
    if (name === wedding) await expect(dialog.getByLabel('How often')).toHaveValue('0')
    await dialog.getByRole('button', { name: 'Remove' }).click()
    await expect(page.getByRole('button', { name: `Edit ${name}` })).toBeHidden()
  }
})

test('a steady monthly expense is suggested as a bill and added in one tap', async ({ page }) => {
  const run = Date.now()
  const bank = `E2E suggest bank ${run}`
  const name = `E2E Hotstar ${run}`
  const monthsAgo = (n: number) => {
    const d = new Date()
    d.setDate(Math.min(d.getDate(), 10))
    d.setMonth(d.getMonth() - n)
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  }

  await page.goto('/accounts')
  await page.getByRole('button', { name: 'Add account' }).first().click()
  const dialog = page.getByRole('dialog', { name: 'Add an account' })
  await dialog.getByLabel('Name').fill(bank)
  await dialog.getByRole('button', { name: 'Add account' }).click()
  await expect(dialog).toBeHidden()
  for (const n of [1, 2, 3]) {
    await page.goto('/transactions?add=EXPENSE')
    const add = page.getByRole('dialog', { name: 'Add expense' })
    await add.getByLabel('Amount').fill('299')
    await add.getByLabel('Category').selectOption({ label: 'Subscriptions' })
    await add.getByLabel('Paid from').selectOption({ label: bank })
    await add.getByLabel('Description').fill(name)
    await add.getByLabel('Date').fill(monthsAgo(n))
    await add.getByRole('button', { name: 'Add', exact: true }).click()
    await expect(add).toBeHidden()
  }

  await page.goto('/plan')
  const suggestion = page.getByTestId(`suggestion-${name}`)
  await expect(suggestion).toContainText('₹299 around the')
  await expect(suggestion).toContainText('seen in 3 months')
  await suggestion.getByRole('button', { name: 'Add to plan' }).click()
  await expect(page.getByRole('button', { name: `Edit ${name}` })).toContainText('Every month')
  await expect(suggestion).toBeHidden()

  // Clean up: the bill, the three expenses, the account.
  await page.getByRole('button', { name: `Edit ${name}` }).click()
  await page.getByRole('dialog', { name: `Edit ${name}` }).getByRole('button', { name: 'Remove' }).click()
  await expect(page.getByRole('button', { name: `Edit ${name}` })).toBeHidden()
  await page.goto('/transactions')
  await page.getByLabel('Filter by account').selectOption({ label: bank })
  for (let i = 0; i < 3; i++) {
    await page.getByRole('button', { name: `Edit ${name}` }).first().click()
    await page.getByRole('dialog', { name: 'Edit expense' }).getByRole('button', { name: 'Delete' }).click()
    await expect(page.getByRole('dialog', { name: 'Edit expense' })).toBeHidden()
  }
  await expect(page.getByText('Nothing here yet')).toBeVisible()
  await page.goto('/accounts')
  await page.getByRole('button', { name: `Edit ${bank}` }).click()
  await page.getByRole('dialog', { name: `Edit ${bank}` }).getByRole('button', { name: 'Archive' }).click()
  await expect(page.getByRole('button', { name: `Edit ${bank}` })).toBeHidden()
})
