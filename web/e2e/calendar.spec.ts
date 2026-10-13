import { expect, test } from '@playwright/test'
import { signIn } from './helpers'

test.beforeEach(async ({ page }) => {
  await signIn(page)
})

const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`

// Nothing is saved: the dialogs open with the day filled in and are cancelled.
test('a day on the plan calendar offers to add a bill on it, or record an expense on it', async ({ page }) => {
  await page.goto('/plan')
  await page.getByRole('radio', { name: 'Calendar' }).click()
  const today = iso(new Date())

  await page.getByTestId(`day-${today}`).click()
  const menu = page.getByRole('menu')
  await expect(menu).toBeVisible()
  await menu.getByRole('menuitem', { name: 'Add a bill or saving due this day' }).click()
  const dialog = page.getByRole('dialog', { name: 'Add to your plan' })
  await expect(dialog.getByLabel('Next due')).toHaveValue(today)
  await dialog.getByRole('button', { name: 'Cancel' }).click()

  await page.getByTestId(`day-${today}`).click()
  await page.getByRole('menu').getByRole('menuitem', { name: 'Record an expense on this day' }).click()
  await expect(page).toHaveURL(/\/transactions\?.*add=EXPENSE/)
  const add = page.getByRole('dialog', { name: 'Add expense' })
  await expect(add.getByLabel('Date')).toHaveValue(today)
  await add.getByRole('button', { name: 'Cancel' }).click()
  await expect(page).not.toHaveURL(/date=/)
})
