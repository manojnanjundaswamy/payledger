import { expect, test } from '@playwright/test'
import { callApi, signUp } from './helpers'

test.use({ storageState: { cookies: [], origins: [] } })

test('a first entry earns a badge, celebrated once, and progress shows the level, streak and challenge', async ({ page, request }) => {
  await signUp(page, request, `e2e-progress-${Date.now()}@example.com`)
  await page.getByTestId('region-setup').getByRole('button', { name: 'Later' }).click()
  await page.getByTestId('make-it-yours').getByRole('button', { name: 'Keep the defaults' }).click()
  await page.getByTestId('tour-intro').getByRole('button', { name: 'Skip' }).click()

  const me = await callApi<{ spaces: { id: string }[] }>(page, 'GET', '/api/core/me')
  const space = me.spaces[0]!.id
  const account = await callApi<{ id: string }>(page, 'POST', `/api/core/spaces/${space}/accounts`, { kind: 'BANK', name: 'E2E Bank' })
  const categories = await callApi<{ id: string; kind: string }[]>(page, 'GET', `/api/core/spaces/${space}/categories`)
  await callApi(page, 'POST', `/api/core/spaces/${space}/transactions`, {
    kind: 'EXPENSE', amount: 25000, accountId: account.id, categoryId: categories.find((c) => c.kind === 'EXPENSE')!.id,
    occurredOn: new Date().toISOString().slice(0, 10), description: 'E2E coffee',
  })

  await page.goto('/progress')
  await expect(page.getByText('Badge earned: First entry')).toBeVisible()
  await expect(page.getByTestId('level')).toContainText('Seedling')
  await expect(page.getByTestId('streak')).toContainText('1 day')
  await expect(page.getByTestId('challenge')).toBeVisible()
  await expect(page.locator('[data-earned="true"]', { hasText: 'First entry' })).toBeVisible()
  await expect(page.locator('[data-earned="false"]', { hasText: 'On budget' })).toContainText('every budget')

  // The bell has it too.
  const inbox = await callApi<{ items: { title: string }[] }>(page, 'GET', '/api/core/me/inbox?limit=30')
  expect(inbox.items.some((i) => i.title === 'Badge earned: First entry')).toBe(true)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})
