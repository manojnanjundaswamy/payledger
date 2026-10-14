import { expect, test } from '@playwright/test'
import { callApi, signIn } from './helpers'

test('an account card shows this month and its latest entry, and a category row its month, count and budget', async ({ page }) => {
  await signIn(page)
  const run = Date.now()
  const me = await callApi<{ spaces: { id: string }[] }>(page, 'GET', '/api/core/me')
  const base = `/api/core/spaces/${me.spaces[0]!.id}`
  const categories = await callApi<{ id: string; name: string }[]>(page, 'GET', `${base}/categories`)
  const account = await callApi<{ id: string }>(page, 'POST', `${base}/accounts`, { kind: 'WALLET', name: `E2E card ${run}`, openingBalance: 0, openingDate: '2026-01-01' })
  const now = new Date()
  const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
  const t = await callApi<{ id: string; version: number }>(page, 'POST', `${base}/transactions`, {
    kind: 'EXPENSE', amount: 45_600, accountId: account.id, categoryId: categories.find((c) => c.name === 'Shopping')!.id, occurredOn: today, description: `E2E shoes ${run}`,
  })

  await page.goto('/accounts')
  const card = page.getByTestId(`account-E2E card ${run}`)
  await expect(card.getByTestId('account-month')).toContainText('−₹456')
  await expect(card).toContainText(`Latest: E2E shoes ${run}`)
  await card.getByRole('link', { name: 'Add expense' }).click()
  await expect(page.getByRole('dialog', { name: 'Add expense' })).toBeVisible()
  await page.keyboard.press('Escape')

  await page.goto('/categories')
  await expect(page.getByTestId('categories-summary')).toContainText('Spent this month')
  await expect(page.getByTestId('category-Shopping').getByTestId('category-month')).not.toHaveText('—')

  await callApi(page, 'DELETE', `${base}/transactions/${t.id}?version=${t.version}`)
  await callApi(page, 'PATCH', `${base}/accounts/${account.id}`, { archived: true })
})
