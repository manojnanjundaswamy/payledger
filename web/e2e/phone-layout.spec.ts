import { expect, test } from '@playwright/test'
import { signIn } from './helpers'

// Every main page fits a phone's width: nothing pokes out, so nothing needs a sideways scroll to be seen.
const PAGES = ['/dashboard', '/transactions', '/transactions?view=calendar', '/budgets', '/accounts', '/categories', '/plan', '/goals',
  '/reports', '/tax', '/groups', '/people', '/utilities', '/settings', '/import', '/household', '/capture']

test('main pages fit a phone screen @phone', async ({ page }) => {
  test.setTimeout(180_000)
  await signIn(page)
  for (const path of PAGES) {
    await page.goto(path)
    await page.waitForLoadState('networkidle')
    const width = await page.evaluate(() => ({ page: document.documentElement.scrollWidth, screen: document.documentElement.clientWidth }))
    expect(width.page, `${path} is wider than the screen`).toBeLessThanOrEqual(width.screen)
  }
  // A page from More says so in the tab bar.
  await page.goto('/plan')
  await expect(page.getByRole('button', { name: 'More: Plan' })).toBeVisible()
  // The calendar shows the whole week.
  await page.goto('/transactions?view=calendar')
  const grid = page.getByRole('grid').first()
  expect(await grid.evaluate((el) => el.scrollWidth <= el.clientWidth + 1)).toBe(true)
})
