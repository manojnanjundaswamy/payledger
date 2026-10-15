import { expect, test } from '@playwright/test'
import { callApi, signIn } from './helpers'

// Staging has no AI key: quick add gives the simple reading (amount and words) as a draft to check. The AI path
// (sure entries added on their own, masking, the cost log) is proven in core-service's AiIntegrationTest.
test('say or type it: the dashboard bar opens quick add, a draft is checked and added', async ({ page }) => {
  await signIn(page)
  const run = Date.now()
  const me = await callApi<{ spaces: { id: string; kind: string }[] }>(page, 'GET', '/api/core/me')
  const space = me.spaces.find((s) => s.kind === 'PERSONAL')!.id
  const wallet = `E2E Quick wallet ${run}`
  await callApi(page, 'POST', `/api/core/spaces/${space}/accounts`, { kind: 'CASH', name: wallet, openingBalance: 0 })
  const rupees = 100 + (run % 800)

  await page.getByRole('button', { name: /Say or type what you spent/ }).click()
  const sheet = page.getByRole('dialog', { name: 'Say or type it' })
  await expect(sheet).toBeVisible()
  await expect(sheet.getByRole('button', { name: 'Speak an entry' })).toBeVisible()
  await sheet.getByLabel('What you spent or received').fill(`${rupees} E2E quick coffee ${run}`)
  await sheet.getByRole('button', { name: 'Add', exact: true }).click()

  await expect(sheet.getByText('Check and add')).toBeVisible()
  await expect(sheet.getByLabel('Amount')).toHaveValue(String(rupees))
  await expect(sheet.getByLabel('Description')).toHaveValue(new RegExp(`E2E quick coffee ${run}`, 'i'))
  await sheet.getByLabel('Account').click()
  await page.getByRole('textbox', { name: 'Search the list' }).fill(wallet)
  await page.getByRole('option', { name: wallet, exact: true }).click()
  await sheet.getByRole('button', { name: 'Add', exact: true }).last().click()
  await expect(sheet.getByText(/^Added:/)).toBeVisible()
  await sheet.getByRole('button', { name: 'Done' }).click()

  await page.goto(`/transactions?q=${encodeURIComponent(`quick coffee ${run}`)}`)
  await expect(page.getByText(new RegExp(`E2E quick coffee ${run}`, 'i')).first()).toBeVisible()
})

test('the Add menu offers "Say or type it" first', async ({ page }) => {
  await signIn(page)
  await page.getByRole('button', { name: 'Add', exact: true }).first().click()
  await page.getByRole('menuitem', { name: /Say or type it/ }).click()
  await expect(page.getByRole('dialog', { name: 'Say or type it' })).toBeVisible()
})

test('on a phone the + opens quick add straight away @phone', async ({ page }) => {
  await signIn(page)
  await page.getByRole('navigation', { name: 'Main' }).getByRole('button', { name: 'Add' }).click()
  const sheet = page.getByRole('dialog', { name: 'Say or type it' })
  await expect(sheet).toBeVisible()
  // It fits the screen, and the by-hand way is still there.
  const box = await sheet.boundingBox()
  expect(box!.x + box!.width).toBeLessThanOrEqual(page.viewportSize()!.width + 1)
  await expect(sheet.getByRole('button', { name: 'Expense' })).toBeVisible()
})
