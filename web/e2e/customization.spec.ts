import { expect, test } from '@playwright/test'
import { dashboardReady, resetPreferences, signIn, waitForSave, widgetOrder } from './helpers'

test.beforeEach(async ({ page }) => {
  await signIn(page)
  await resetPreferences(page)
})

test('a customised dashboard and theme come back exactly the same after signing in again', async ({ page }) => {
  const before = await widgetOrder(page)
  expect(before.slice(0, 2)).toEqual(['gettingStarted', 'safeToSpend'])

  // Rearrange with the keyboard (the accessible path; mouse and touch use the same code).
  await page.getByRole('button', { name: 'Customize' }).click()
  const handle = page.getByRole('button', { name: 'Move Getting started' })
  await handle.focus()
  await page.keyboard.press('Space')
  await page.keyboard.press('ArrowRight')
  await page.keyboard.press('Space')
  await expect.poll(() => widgetOrder(page)).toEqual(['safeToSpend', 'gettingStarted', ...before.slice(2)])

  // Resize one, add a note with text.
  await page.getByRole('radiogroup', { name: 'Size of Safe to spend' }).getByRole('radio', { name: 'XL' }).click()
  await page.getByRole('button', { name: 'Add widget' }).click()
  await page.getByRole('dialog').getByRole('button', { name: /^Note/ }).click()
  await page.getByRole('textbox', { name: 'Note' }).fill('Pay the LIC premium before the 2nd')
  await page.getByRole('button', { name: 'Done' }).click()

  // A second dashboard from a template.
  await page.getByRole('button', { name: 'New dashboard' }).click()
  await page.getByRole('textbox', { name: 'Dashboard name' }).fill('Home')
  await page.getByRole('radio', { name: /Household/ }).click()
  await page.getByRole('button', { name: 'Create dashboard' }).click()
  await expect(page.getByRole('tab', { name: 'Home', selected: true })).toBeVisible()
  await page.getByRole('tab', { name: 'Overview' }).click()
  // goto() reloads the app; it would load the profile before the dashboard changes are saved.
  await waitForSave(page)

  // Theme: dark with an indigo accent.
  await page.goto('/settings?tab=appearance')
  await page.getByRole('radio', { name: 'Dark' }).click()
  await page.getByRole('radio', { name: 'Indigo' }).click()
  await waitForSave(page)

  const expectCustomised = async () => {
    await page.goto('/dashboard')
    await dashboardReady(page)
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
    expect(await page.evaluate(() => document.documentElement.style.getPropertyValue('--accent'))).toBe('#4f46e5')
    await expect(page.getByRole('tab', { name: 'Home' })).toBeVisible()
    const order = await widgetOrder(page)
    expect(order[0]).toBe('note')
    expect(order.slice(1, 3)).toEqual(['safeToSpend', 'gettingStarted'])
    await expect(page.locator('[data-widget-type="safeToSpend"]')).toHaveClass(/xl:col-span-4/)
    await expect(page.getByRole('textbox', { name: 'Note' })).toHaveValue('Pay the LIC premium before the 2nd')
  }

  await expectCustomised()

  // A fresh browser session: sign out, sign in again.
  await page.getByRole('button', { name: 'Account menu' }).click()
  await page.getByRole('menuitem', { name: 'Sign out' }).click()
  await page.waitForURL((url) => url.pathname === '/')
  await page.context().clearCookies()
  await signIn(page)
  await expectCustomised()
})

test('removing a widget can be undone', async ({ page }) => {
  await page.getByRole('button', { name: 'Customize' }).click()
  await page.getByRole('button', { name: 'Options for Budgets' }).click()
  await page.getByRole('menuitem', { name: 'Remove' }).click()
  expect(await widgetOrder(page)).not.toContain('budgets')
  await page.getByRole('button', { name: 'Undo' }).click()
  await expect.poll(() => widgetOrder(page)).toContain('budgets')
})

test('the command palette navigates and changes the theme', async ({ page }) => {
  await page.keyboard.press('Control+k')
  await page.getByPlaceholder('Type a command or search…').fill('dark theme')
  await page.keyboard.press('Enter')
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await page.keyboard.press('Control+k')
  await page.getByPlaceholder('Type a command or search…').fill('groups')
  await page.getByRole('option', { name: 'Groups', exact: true }).click()
  await expect(page).toHaveURL(/\/groups$/)
  await expect(page.getByRole('heading', { name: 'Split with anyone' })).toBeVisible()
})

test('works on a phone: bottom navigation and the More sheet @phone', async ({ page }) => {
  await expect(page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Budgets' })).toBeVisible()
  await page.getByRole('button', { name: 'More' }).click()
  await page.getByRole('dialog').getByRole('link', { name: 'Settings' }).click()
  await expect(page).toHaveURL(/\/settings/)
})
