import { expect, test, type Page } from '@playwright/test'
import { signUp } from './helpers'

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
  await page.getByTestId('make-it-yours').getByRole('button', { name: 'Keep the defaults' }).click()
  await page.getByTestId('tour-intro').getByRole('button', { name: 'Start the tour' }).click()
  const done = await walkTour(page)
  await done.click()
  await expect(page.getByTestId('tour')).toBeHidden()
})
