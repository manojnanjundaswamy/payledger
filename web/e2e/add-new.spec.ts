import { expect, test } from '@playwright/test'
import { signIn } from './helpers'

test('a dropdown adds what it lacks: a new category from the expense form, chosen when saved', async ({ page }) => {
  await signIn(page)
  const name = `E2E Pets ${Date.now()}`
  await page.goto('/transactions?add=EXPENSE')
  const add = page.getByRole('dialog', { name: 'Add expense' })
  await add.getByLabel('Category').click()
  await page.getByRole('textbox', { name: 'Search the list' }).fill(name)
  await page.getByRole('option', { name: 'Add a new category…' }).click()

  const form = page.getByRole('dialog', { name: 'Add a spending category' })
  await expect(form.getByLabel('Name')).toHaveValue(name)
  await form.getByRole('button', { name: /Add|Save/ }).last().click()
  await expect(form).toBeHidden()
  await expect(add.getByLabel('Category')).toContainText(name)
})
