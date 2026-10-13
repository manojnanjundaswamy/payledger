import { expect, test } from '@playwright/test'
import { signIn } from './helpers'

test.beforeEach(async ({ page }) => {
  await signIn(page)
})

// The demo user is an owner of the admin console locally (ADMIN_EMAILS in docker-compose.yml).
test('staff open the admin console from the sidebar and see the numbers', async ({ page }) => {
  await page.goto('/dashboard')
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Admin' }).click()
  await expect(page).toHaveURL(/\/admin/)
  await expect(page.getByTestId('staff-role')).toHaveText('Owner')
  await expect(page.getByTestId('kpi-users')).toContainText(/\d/)
  // Signing in just now made the demo user active today.
  await expect(page.getByTestId('kpi-daily').locator('.text-2xl')).not.toHaveText('0')
  await expect(page.getByText('Do people come back?')).toBeVisible()
  await expect(page.getByText(/staff never see anyone's amounts/)).toBeVisible()

  await page.getByRole('radio', { name: '7 days' }).click()
  await expect(page.getByRole('img', { name: /Active users and sign-ups per day/ })).toBeVisible()
})
