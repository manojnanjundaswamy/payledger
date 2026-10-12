import { expect, type Page } from '@playwright/test'

export const DEMO = { email: 'demo@payledger.local', password: 'demo-password' }

export async function signIn(page: Page) {
  await page.goto('/')
  await page.getByRole('link', { name: 'Sign in' }).first().click()
  await page.locator('#username').fill(DEMO.email)
  await page.locator('#password').fill(DEMO.password)
  await page.locator('#kc-login').click()
  await page.waitForURL('**/dashboard')
  await dashboardReady(page)
}

/** The URL changes before the profile has loaded; wait for the rendered dashboard. */
export async function dashboardReady(page: Page) {
  await expect(page.getByRole('button', { name: 'Customize' })).toBeVisible()
  await expect(page.getByTestId('widget-grid')).toBeVisible()
}

/** Start every test from a clean slate: default theme and the default dashboard. */
export async function resetPreferences(page: Page) {
  const xsrf = (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')?.value ?? ''
  const res = await page.request.patch('/api/core/me', { data: { preferences: {} }, headers: { 'X-XSRF-TOKEN': decodeURIComponent(xsrf) } })
  expect(res.ok()).toBeTruthy()
  await page.reload()
  await dashboardReady(page)
}

export async function widgetOrder(page: Page): Promise<string[]> {
  return page.locator('[data-testid="widget-grid"] > [data-widget-id]').evaluateAll((els) => els.map((e) => e.getAttribute('data-widget-type')!))
}

/** Customisation is saved shortly after the change; wait until the indicator says so. */
export async function waitForSave(page: Page) {
  await expect(page.getByRole('status', { name: 'All changes saved to your account' })).toBeVisible({ timeout: 10_000 })
}
