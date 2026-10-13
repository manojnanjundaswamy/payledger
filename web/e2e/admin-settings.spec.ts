import { expect, test } from '@playwright/test'
import { signIn } from './helpers'

test.beforeEach(async ({ page }) => {
  await signIn(page)
})

// Settings are global: the test only turns an announcement on and off again, and leaves the limits as they were.
test('an announcement set in the admin console shows to everyone and can be dismissed', async ({ page }) => {
  const message = `E2E notice ${Date.now()}`
  await page.goto('/admin?tab=settings')
  await expect(page.getByLabel('Open accounts')).toBeVisible()
  const announce = page.getByRole('switch', { name: 'Show an announcement' })
  if (await announce.getAttribute('aria-checked') !== 'true') await announce.click()
  await page.getByLabel('Message').fill(message)
  await page.getByRole('button', { name: 'Save settings' }).click()
  await expect(page.getByText('Settings saved')).toBeVisible()

  await page.goto('/dashboard')
  await expect(page.getByTestId('announcement')).toContainText(message)
  await page.getByRole('button', { name: 'Dismiss announcement' }).click()
  await expect(page.getByTestId('announcement')).toBeHidden()

  await page.goto('/admin?tab=settings')
  await page.getByRole('switch', { name: 'Show an announcement' }).click()
  await page.getByRole('button', { name: 'Save settings' }).click()
  await expect(page.getByText('Settings saved')).toBeVisible()

  await page.goto('/admin?tab=audit')
  await expect(page.getByTestId('audit-log')).toContainText('changed the settings')
})

test('the staff tab shows the configured owner, who cannot be changed there', async ({ page }) => {
  await page.goto('/admin?tab=staff')
  await expect(page.getByTestId('staff-list')).toContainText('demo@payledger.local')
  await expect(page.getByTestId('staff-list')).toContainText('ADMIN_EMAILS')
  await expect(page.getByRole('combobox', { name: 'Role of demo@payledger.local' })).toHaveCount(0)
})
