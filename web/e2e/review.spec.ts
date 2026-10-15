import { expect, test } from '@playwright/test'
import { callApi, signUp } from './helpers'

test('a month in review compares with the month before, and the dashboard can show it', async ({ page, request }) => {
  await signUp(page, request, `e2e-review-${Date.now()}@example.com`)
  const me = await callApi<{ spaces: { id: string }[] }>(page, 'GET', '/api/core/me')
  const space = me.spaces[0]!.id
  const account = await callApi<{ id: string }>(page, 'POST', `/api/core/spaces/${space}/accounts`, { kind: 'BANK', name: 'E2E Bank' })
  const cats = await callApi<{ id: string; kind: string; name: string }[]>(page, 'GET', `/api/core/spaces/${space}/categories`)
  const food = cats.find((c) => c.name === 'Groceries')!.id
  const salary = cats.find((c) => c.kind === 'INCOME')!.id
  const add = (kind: string, amount: number, categoryId: string, on: string) => callApi(page, 'POST', `/api/core/spaces/${space}/transactions`,
    { kind, amount, accountId: account.id, categoryId, occurredOn: on, description: `E2E ${kind.toLowerCase()}` })
  await add('EXPENSE', 1_000_000, food, '2026-08-10')
  await add('INCOME', 10_000_000, salary, '2026-09-01')
  await add('EXPENSE', 1_500_000, food, '2026-09-12')

  await page.goto('/review?month=2026-09')
  await expect(page.getByTestId('review-month')).toContainText('September 2026')
  await expect(page.getByTestId('review-highlights')).toContainText('50% higher than the month before')
  await expect(page.getByText('Groceries').first()).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})
