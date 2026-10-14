import { expect, type APIRequestContext, type Page } from '@playwright/test'

export const DEMO = { email: 'demo@payledger.local', password: 'demo-password' }

export async function signIn(page: Page) {
  await page.goto('/')
  await page.getByRole('link', { name: 'Sign in' }).first().click()
  await page.locator('#username').fill(DEMO.email)
  await page.locator('#password').fill(DEMO.password)
  await page.locator('#kc-login').click()
  await page.waitForURL('**/dashboard')
  await dashboardReady(page)
}

/** The URL changes before the profile has loaded; wait for the rendered dashboard. */
export async function dashboardReady(page: Page) {
  await expect(page.getByRole('button', { name: 'Customize' })).toBeVisible()
  await expect(page.getByTestId('widget-grid')).toBeVisible()
}

/** Start every test from a clean slate: default theme and the default dashboard. */
export async function resetPreferences(page: Page) {
  const xsrf = (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')?.value ?? ''
  const res = await page.request.patch('/api/core/me', { data: { preferences: {} }, headers: { 'X-XSRF-TOKEN': decodeURIComponent(xsrf) } })
  expect(res.ok()).toBeTruthy()
  await page.reload()
  await dashboardReady(page)
}

export async function widgetOrder(page: Page): Promise<string[]> {
  return page.locator('[data-testid="widget-grid"] > [data-widget-id]').evaluateAll((els) => els.map((e) => e.getAttribute('data-widget-type')!))
}

/** Customisation is saved shortly after the change; wait until the indicator says so. */
export async function waitForSave(page: Page) {
  await expect(page.getByRole('status', { name: 'All changes saved to your account' })).toBeVisible({ timeout: 10_000 })
}

export const MAILPIT = 'http://localhost:8025'

/** A brand-new person: sign up in Keycloak, verify the email through Mailpit, land in the app. */
export async function signUp(page: Page, request: APIRequestContext, email: string) {
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


/** Calls the API as the signed-in page (session cookie and CSRF token), for quick test setup and clean-up. */
export async function callApi<T = unknown>(page: Page, method: string, path: string, body?: unknown): Promise<T> {
  return page.evaluate(async ({ method, path, body }) => {
    const token = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.slice('XSRF-TOKEN='.length)
    const res = await fetch(path, {
      method,
      headers: { 'Content-Type': 'application/json', ...(token ? { 'X-XSRF-TOKEN': decodeURIComponent(token) } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    if (!res.ok) throw new Error(`${method} ${path}: ${res.status} ${await res.text()}`)
    return res.status === 204 ? null : res.json()
  }, { method, path, body }) as Promise<T>
}
