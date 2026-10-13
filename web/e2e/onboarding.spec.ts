import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

const MAILPIT = 'http://localhost:8025'

/** A brand-new person: sign up in Keycloak, verify the email through Mailpit, land in the app. */
async function signUp(page: Page, request: APIRequestContext, email: string) {
  await page.goto('/oauth2/authorization/keycloak?signup')
  await page.locator('#email').fill(email)
  await page.locator('#firstName').fill('Asha')
  await page.locator('#lastName').fill('Test')
  const password = page.locator('#password')
  // Keycloak 26.7+ asks for the password after verifying the email; older versions on this form.
  if (await password.count()) {
    await password.fill('correct-horse-9')
    await page.locator('#password-confirm').fill('correct-horse-9')
  }
  await page.locator('#kc-register-form [type=submit]').click()
  let link = ''
  await expect.poll(async () => {
    const found = await (await request.get(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:${email}`)}`)).json()
    if (!found.messages_count) return ''
    const message = await (await request.get(`${MAILPIT}/api/v1/message/${found.messages[0].ID}`)).json()
    link = (message.Text as string).match(/https?:\/\/\S+action-token\S+/)?.[0] ?? ''
    return link
  }, { timeout: 15_000 }).not.toBe('')
  await page.goto(link)
  if (await page.locator('#password-new').count()) {
    await page.locator('#password-new').fill('correct-horse-9')
    await page.locator('#password-confirm').fill('correct-horse-9')
    await page.locator('[type=submit]').click()
  }
  // Some Keycloak versions show "Click here to proceed" after verification.
  const proceed = page.getByRole('link', { name: /proceed|back to application/i })
  if (await proceed.count()) await proceed.first().click()
  await page.waitForURL('**/dashboard')
}

test('a new person sets up accounts, payday, bills and budgets in one go, and it all shows up', async ({ page, request }) => {
  await signUp(page, request, `e2e-setup-${Date.now()}@example.com`)

  await page.getByRole('link', { name: /Set up your accounts and bills/ }).click()
  await expect(page).toHaveURL(/\/welcome/)

  await page.getByLabel('Which day does your salary come?').selectOption('25')
  await page.getByLabel('Monthly take-home pay (optional)').fill('80,000')
  await page.getByRole('button', { name: 'Next' }).click()

  await page.getByLabel('Account 1 name').fill('HDFC salary')
  await page.getByLabel('Account 1 balance').fill('40,000')
  await page.getByRole('button', { name: 'Next' }).click()

  await page.getByRole('checkbox', { name: 'Rent' }).check()
  await page.getByLabel('Rent amount').fill('20,000')
  await page.getByRole('checkbox', { name: 'Netflix' }).check()
  await page.getByRole('button', { name: 'Next' }).click()

  // 10% of ₹80,000 for groceries.
  await expect(page.getByLabel('Groceries budget')).toHaveValue('8000')
  await page.getByRole('button', { name: 'Finish setup' }).click()

  await page.waitForURL('**/dashboard')
  await expect(page.getByText('You’re set up')).toBeVisible()

  await page.goto('/plan')
  await expect(page.getByRole('button', { name: 'Edit Rent' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Edit Netflix' })).toBeVisible()
  await page.goto('/budgets')
  await expect(page.getByTestId('budget-card-Groceries')).toContainText('₹8,000')
  await page.goto('/accounts')
  await expect(page.getByTestId('balance-HDFC salary')).toContainText('40,000', { timeout: 15_000 })
  await page.goto('/settings?tab=regional')
  await expect(page.getByText(/25/).first()).toBeVisible()

  // Running it again is refused instead of doubling everything.
  await page.goto('/welcome')
  for (let i = 0; i < 3; i++) await page.getByRole('button', { name: 'Next' }).click()
  await page.getByRole('button', { name: 'Finish setup' }).click()
  await expect(page.getByRole('alert')).toContainText('You already have accounts')
})
