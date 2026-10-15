import { expect, test } from '@playwright/test'
import { signIn } from './helpers'

test('the account page opens for password and email (no "unexpected error")', async ({ page }) => {
  await signIn(page)
  const { accountUrl } = await page.evaluate(async () => (await fetch('/api/session')).json())
  await page.goto(`${accountUrl}/account-security/signing-in`)
  await expect(page.getByRole('heading', { name: /Signing in/i }).first()).toBeVisible({ timeout: 20_000 })
  await expect(page.getByText(/unexpected error/i)).toHaveCount(0)
  await expect(page.getByText(/Password/).first()).toBeVisible()

  // Personal info: the email can be changed.
  await page.goto(`${accountUrl}/`)
  const email = page.getByRole('textbox', { name: /email/i })
  await expect(email).toBeVisible({ timeout: 20_000 })
  await expect(email).toBeEditable()
})
