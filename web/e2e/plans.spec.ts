import { expect, test } from '@playwright/test'
import { signIn } from './helpers'

test('the landing page shows the two plans', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Free for a complete budget' })).toBeVisible()
  await expect(page.getByText('Up to 5 open accounts')).toBeVisible()
  await expect(page.getByText(/₹99 a month or ₹799 a year/)).toBeVisible()
})

test('settings show the plan; the demo user has complimentary Premium', async ({ page }) => {
  await signIn(page)
  await page.goto('/settings?tab=plan')
  await expect(page.getByTestId('plan-name')).toHaveText('Premium')
  await expect(page.getByText('Complimentary: on us.')).toBeVisible()
  // Premium has no limits to show; the comparison is still there.
  await expect(page.getByRole('list', { name: 'Free plan limits' })).toBeHidden()
  await expect(page.getByRole('region', { name: 'Compare plans' })).toContainText('Import from Excel or CSV')
})
