import { expect, test, type Page } from '@playwright/test'
import { callApi, signUp } from './helpers'

// Starts without the flag the other tests set, so the welcome and tour show as for a real new person.
test.use({ storageState: { cookies: [], origins: [] } })

async function walkTour(page: Page) {
  const tour = page.getByTestId('tour')
  await expect(tour).toBeVisible()
  for (let i = 0; i < 12; i++) {
    const done = tour.getByRole('button', { name: 'Done' })
    if (await done.isVisible()) return done
    // The card stays inside the screen.
    const box = (await tour.getByRole('dialog').boundingBox())!
    const width = page.viewportSize()!.width
    expect(box.x).toBeGreaterThanOrEqual(15)
    expect(box.x + box.width).toBeLessThanOrEqual(width - 15)
    await tour.getByRole('button', { name: 'Next' }).click()
  }
  throw new Error('the tour never ended')
}

test('a new person makes it theirs, takes the tour, and can turn it off', async ({ page, request, context }) => {
  await signUp(page, request, `e2e-tour-${Date.now()}@example.com`)
  // First: where they live. Picking a country fills in its currency, time zone and financial year.
  const region = page.getByTestId('region-setup')
  await expect(region).toBeVisible()
  await region.getByLabel('Where you live').click()
  await page.getByRole('option', { name: /United Arab Emirates/ }).click()
  await expect(region.getByLabel('Currency')).toHaveValue('AED')
  await expect(region.getByLabel('Time zone')).toHaveValue('Asia/Dubai')
  await expect(region.getByLabel('Financial year starts')).toHaveValue('1')
  await region.getByLabel('Your month starts on').selectOption('25')
  await region.getByRole('button', { name: 'Save and continue' }).click()
  await expect(region).toBeHidden()
  const me = await callApi<{ settings: { country: string; budgetStartDay: number }; spaces: { currency: string }[] }>(page, 'GET', '/api/core/me')
  expect(me.settings.country).toBe('AE')
  expect(me.settings.budgetStartDay).toBe(25)
  expect(me.spaces[0]!.currency).toBe('AED')
  const welcome = page.getByTestId('make-it-yours')
  await expect(welcome).toBeVisible()
  await expect(welcome).toContainText('Make it yours')
  await welcome.getByTestId('palette-bumblebee').click()
  await expect(page.locator('html')).toHaveAttribute('data-palette', 'bumblebee')
  await welcome.getByRole('button', { name: 'Looks good' }).click()

  // The tour: Skip without ticking, and it's offered again at the next sign-in (a new tab here).
  const intro = page.getByTestId('tour-intro')
  await expect(intro).toBeVisible()
  await intro.getByRole('button', { name: 'Skip' }).click()
  await expect(intro).toBeHidden()
  await page.reload()
  await expect(page.getByTestId('widget-grid')).toBeVisible()
  await expect(intro).toBeHidden() // same session: not again

  const again = await context.newPage()
  await again.goto('/dashboard')
  const intro2 = again.getByTestId('tour-intro')
  await expect(intro2).toBeVisible()
  await expect(again.getByTestId('make-it-yours')).toBeHidden() // the welcome is once
  await expect(again.getByTestId('region-setup')).toBeHidden() // and the region, once saved
  await intro2.getByLabel('Don’t show this again').check()
  await intro2.getByRole('button', { name: 'Start the tour' }).click()
  const done = await walkTour(again)
  await done.click()
  await expect(again.getByTestId('tour')).toBeHidden()

  // Ticked: not in a new session either.
  await again.waitForTimeout(1200) // the preference saves shortly after
  const third = await context.newPage()
  await third.goto('/dashboard')
  await expect(third.getByTestId('widget-grid')).toBeVisible()
  await expect(third.getByTestId('tour-intro')).toBeHidden()
  await expect(third.locator('html')).toHaveAttribute('data-palette', 'bumblebee')
})

test('the tour fits a phone @phone', async ({ page, request }) => {
  await signUp(page, request, `e2e-tour-phone-${Date.now()}@example.com`)
  // "Later" skips the region for this visit only.
  await page.getByTestId('region-setup').getByRole('button', { name: 'Later' }).click()
  await page.getByTestId('make-it-yours').getByRole('button', { name: 'Keep the defaults' }).click()
  await page.getByTestId('tour-intro').getByRole('button', { name: 'Start the tour' }).click()
  const done = await walkTour(page)
  await done.click()
  await expect(page.getByTestId('tour')).toBeHidden()
})

test('getting started ticks each step as it is done, and goes away when all are', async ({ page, request }) => {
  await signUp(page, request, `e2e-setup-${Date.now()}@example.com`)
  // Onboarding cards on for this test only through the region (the rest is skipped).
  await page.getByTestId('region-setup').getByRole('button', { name: 'Save and continue' }).click()
  await page.getByTestId('make-it-yours').getByRole('button', { name: 'Keep the defaults' }).click()
  await page.getByTestId('tour-intro').getByRole('button', { name: 'Skip' }).click()
  const steps = page.getByTestId('setup-steps')
  await expect(steps.locator('[data-done="true"]', { hasText: 'Set your region and payday' })).toBeVisible()
  await expect(steps.locator('[data-done="true"]', { hasText: 'Make it yours' })).toBeVisible()
  await expect(steps.locator('[data-done="false"]', { hasText: 'Set your first budget' })).toBeVisible()

  // An account and a budget: those tick at once.
  const me = await callApi<{ spaces: { id: string }[] }>(page, 'GET', '/api/core/me')
  const space = me.spaces[0]!.id
  await callApi(page, 'POST', `/api/core/spaces/${space}/accounts`, { kind: 'BANK', name: 'E2E Bank' })
  const categories = await callApi<{ id: string; kind: string }[]>(page, 'GET', `/api/core/spaces/${space}/categories`)
  await callApi(page, 'PUT', `/api/core/spaces/${space}/budgets/${categories.find((c) => c.kind === 'EXPENSE')!.id}`, { amount: 500000 })
  await page.reload()
  await expect(steps.locator('[data-done="true"]', { hasText: 'Add your accounts' })).toBeVisible()
  await expect(steps.locator('[data-done="true"]', { hasText: 'Set your first budget' })).toBeVisible()
})
