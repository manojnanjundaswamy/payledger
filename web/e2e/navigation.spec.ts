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

test('pages can be made wide and back in one click', async ({ page }) => {
  await page.goto('/transactions')
  const html = page.locator('html')
  const start = await html.getAttribute('data-width')
  const before = (await page.locator('main > div > .mx-auto').first().boundingBox())!.width
  await page.getByRole('button', { name: start === 'wide' ? 'Narrower pages' : 'Wider pages' }).click()
  await expect(html).toHaveAttribute('data-width', start === 'wide' ? 'comfortable' : 'wide')
  const after = (await page.locator('main > div > .mx-auto').first().boundingBox())!.width
  expect(start === 'wide' ? after < before : after > before).toBe(true)
  // Back to how it was: the demo user's own choice stays theirs.
  await page.getByRole('button', { name: /pages$/ }).click()
  await expect(html).toHaveAttribute('data-width', start ?? 'comfortable')
})
