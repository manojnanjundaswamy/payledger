import { expect, test } from '@playwright/test'
import { callApi, signIn, signUp } from './helpers'

test('"Can I afford it?" answers from the utilities, and the money health score is worked out', async ({ page }) => {
  await signIn(page)
  await page.getByRole('button', { name: 'Open utilities' }).click()
  await page.getByRole('dialog', { name: 'Utilities' }).getByRole('button', { name: 'Can I afford it?' }).click()
  const win = page.getByTestId('window-afford')
  await win.getByLabel('It costs').fill('4,500')
  await win.getByRole('button', { name: 'Check' }).click()
  await expect(win.getByRole('status')).toBeVisible()
  await win.getByRole('radio', { name: 'Every month' }).click()
  await win.getByRole('button', { name: 'Check' }).click()
  await expect(win.getByRole('status')).toContainText(/month/)

  const me = await callApi<{ spaces: { id: string }[] }>(page, 'GET', '/api/core/me')
  const health = await callApi<{ score: number | null; parts: unknown[] }>(page, 'GET', `/api/core/spaces/${me.spaces[0]!.id}/insights/health`)
  expect(health.score === null || (health.score >= 0 && health.score <= 100)).toBe(true)
})

test('a new dashboard has the money health and "Can I afford it?" cards', async ({ page, request }) => {
  await signUp(page, request, `e2e-health-${Date.now()}@example.com`)
  await expect(page.getByText('Record a month of income and spending')).toBeVisible()
  const afford = page.getByTestId('can-i-afford')
  await afford.getByLabel('It costs').fill('20000')
  await afford.getByRole('button', { name: 'Check' }).click()
  await expect(afford.getByRole('status')).toBeVisible()
})
