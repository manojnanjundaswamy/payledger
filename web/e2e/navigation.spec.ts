import { expect, test } from '@playwright/test'
import { signIn } from './helpers'

test.beforeEach(async ({ page }) => {
  await signIn(page)
})

test('a page reached from the sidebar is rendered once, not replaced a moment later', async ({ page }) => {
  // Each page used to mount twice (once inside the fading-out old page, then again), so whatever was
  // done in its first moment, like picking a file, was lost when the second copy replaced the first.
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Categories' }).click()
  const first = await page.getByRole('button', { name: 'Add category' }).elementHandle()
  await page.waitForTimeout(600)
  expect(await first!.evaluate((el) => el.isConnected)).toBe(true)
})
