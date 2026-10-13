import { expect, test } from '@playwright/test'
import { signIn } from './helpers'

test.beforeEach(async ({ page }) => {
  await signIn(page)
})

// Only touches the user it creates (the demo account holds real data). Needs the realm's payledger-core-admin
// client: on an older local Keycloak volume, run scripts/keycloak-sync.sh once.
test('staff add a user, who gets an email; give them Premium, disable them, and it is all in the log', async ({ page, request }) => {
  const email = `e2e-admin-${Date.now()}@example.com`

  await page.goto('/admin?tab=users')
  await page.getByRole('button', { name: 'Add user' }).click()
  const dialog = page.getByRole('dialog', { name: 'Add a user' })
  await dialog.getByLabel('Email').fill(email)
  await dialog.getByLabel('Name').fill('E2E Admin Person')
  await dialog.getByRole('button', { name: 'Add user' }).click()

  // The new user opens; Keycloak emailed them a link to verify and choose a password.
  const sheet = page.getByRole('dialog', { name: 'E2E Admin Person' })
  await expect(sheet).toBeVisible()
  await expect(sheet.getByText('Free', { exact: true })).toBeVisible()
  await expect.poll(async () => {
    const r = await request.get(`http://localhost:8025/api/v1/search?query=${encodeURIComponent(`to:${email}`)}`)
    return (await r.json()).messages_count
  }, { timeout: 15_000 }).toBeGreaterThan(0)

  const until = new Date(Date.now() + 30 * 86_400_000).toISOString().slice(0, 10)
  await sheet.getByLabel('Give Premium until').fill(until)
  await sheet.getByRole('button', { name: 'Give Premium' }).click()
  await expect(sheet.getByText('Given by us')).toBeVisible()

  await sheet.getByRole('button', { name: 'Disable account' }).click()
  await sheet.getByRole('button', { name: 'Yes, disable' }).click()
  await expect(sheet.getByText('Disabled', { exact: true })).toBeVisible()
  await expect(sheet.getByTestId('user-history')).toContainText('disabled the account')
  await expect(sheet.getByTestId('user-history')).toContainText('gave Premium')
  await page.keyboard.press('Escape')

  // Found by search, with the filters.
  await page.getByLabel('Search users').fill(email)
  await expect(page.getByTestId('users-total')).toHaveText('1 user')
  await page.getByRole('radio', { name: 'Disabled' }).click()
  await expect(page.getByTestId('users-total')).toHaveText('1 user')
  await page.getByRole('radio', { name: 'Free' }).click()
  await expect(page.getByText('No one matches.')).toBeVisible()

  await page.goto('/admin?tab=audit')
  await expect(page.getByTestId('audit-log')).toContainText(`created the account for ${email}`)
})
