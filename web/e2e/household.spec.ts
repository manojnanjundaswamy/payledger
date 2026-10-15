import { expect, test } from '@playwright/test'
import { signIn } from './helpers'

test('a household: start it, invite someone, switch to its money and back', async ({ page }) => {
  await signIn(page)
  const run = Date.now()
  const name = `E2E home ${run}`

  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Household' }).click()
  await page.getByLabel('Name').fill(name)
  await page.getByRole('button', { name: 'Start the household' }).click()
  await expect(page.getByTestId('household-name')).toContainText(name)

  // Someone without an account: an invite, listed until they join.
  await page.getByLabel('Email').fill(`e2e-family-${run}@example.com`)
  await page.getByRole('button', { name: 'Invite', exact: true }).click()
  await expect(page.getByTestId('invites')).toContainText(`e2e-family-${run}@example.com`)

  // Its money: the switcher says where you are, and the accounts are the household's (none yet).
  await page.getByTestId('open-household-money').click()
  await expect(page.getByTestId('space-switcher')).toContainText(name)
  await page.goto('/accounts')
  await expect(page.getByTestId('space-switcher')).toContainText(name)
  await expect(page.locator('[data-testid^="account-"]')).toHaveCount(0)

  // Back to your own money.
  await page.getByTestId('space-switcher').click()
  await page.getByRole('menuitem', { name: /Personal/ }).click()
  await expect(page.getByTestId('space-switcher')).toContainText('Personal')
  await expect(page.locator('[data-testid^="account-"]').first()).toBeVisible()
})
