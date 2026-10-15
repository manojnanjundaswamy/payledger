import { expect, test } from '@playwright/test'
import { callApi, signUp } from './helpers'

test('switching currency converts the money at the rate shown, after asking how', async ({ page, request }) => {
  await signUp(page, request, `e2e-currency-${Date.now()}@example.com`)
  const me = await callApi<{ spaces: { id: string }[] }>(page, 'GET', '/api/core/me')
  const space = me.spaces[0]!.id
  await callApi(page, 'POST', `/api/core/spaces/${space}/accounts`, { kind: 'BANK', name: 'E2E Bank', openingBalance: 1_00_000_00, openingDate: '2026-01-01' })

  await page.goto('/settings?tab=regional')
  await page.getByLabel('Currency', { exact: true }).click()
  await page.getByRole('textbox', { name: 'Search the list' }).fill('USD')
  await page.getByRole('option', { name: /USD · US Dollar/ }).click()
  const dialog = page.getByTestId('currency-change')
  await expect(dialog.getByRole('radio', { name: /Convert my money/ })).toHaveAttribute('aria-checked', 'true')
  await dialog.getByLabel('My own rate').fill('0.5')
  await expect(dialog).toContainText('$50,000.00')
  await expect(dialog).toContainText('Moves 1 account')
  // The other way is offered too, and says what it does.
  await expect(dialog.getByRole('radio', { name: /Keep the numbers/ })).toContainText('nothing is converted')
  await dialog.getByRole('button', { name: 'Convert to USD' }).click()
  await expect(page.getByText('Converted to USD')).toBeVisible()

  await page.goto('/accounts')
  await expect(page.getByText('$50,000').first()).toBeVisible()
  const after = await callApi<{ spaces: { currency: string }[] }>(page, 'GET', '/api/core/me')
  expect(after.spaces[0]!.currency).toBe('USD')

  // A new account shows the currency it's in.
  await page.goto('/accounts')
  await page.getByRole('button', { name: /Add an account|Add account/ }).first().click()
  const add = page.getByRole('dialog', { name: 'Add an account' })
  await expect(add.locator('[data-currency="USD"]')).toContainText('$')
})
