import { expect, test } from '@playwright/test'
import { signIn } from './helpers'

test.beforeEach(async ({ page }) => {
  await signIn(page)
})

test('add a category with a subcategory, use it, hide it, show it and delete both', async ({ page }) => {
  // The demo user is shared by every run: unique names keep runs apart, and the test deletes what it adds.
  const run = Date.now()
  const pets = `E2E pets ${run}`
  const vet = `E2E vet ${run}`

  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Categories' }).click()
  await expect(page.getByRole('button', { name: 'Edit Groceries' })).toBeVisible()

  await page.getByRole('button', { name: 'Add category' }).click()
  let dialog = page.getByRole('dialog', { name: 'Add a spending category' })
  await dialog.getByLabel('Name').fill(pets)
  await dialog.getByTitle('paw-print').click()
  await dialog.getByTitle('Colour 5').click()
  await expect(dialog.getByLabel('Colour 5')).toBeChecked()
  await dialog.getByRole('button', { name: 'Add category' }).click()
  await expect(dialog).toBeHidden()

  // The same name in different capitals is refused.
  await page.getByRole('button', { name: 'Add category' }).click()
  dialog = page.getByRole('dialog', { name: 'Add a spending category' })
  await dialog.getByLabel('Name').fill(pets.toUpperCase())
  await dialog.getByRole('button', { name: 'Add category' }).click()
  await expect(dialog.getByRole('alert')).toHaveText('You already have a category with that name.')

  await dialog.getByLabel('Name').fill(vet)
  await dialog.getByLabel('Inside').selectOption({ label: pets })
  await dialog.getByRole('button', { name: 'Add category' }).click()
  await expect(dialog).toBeHidden()
  await expect(page.getByRole('button', { name: `Edit ${vet}` })).toBeVisible()

  // The transaction form offers it with its parent's name.
  await page.goto('/transactions?add=EXPENSE')
  const add = page.getByRole('dialog', { name: 'Add expense' })
  await expect(add.getByLabel('Category').getByRole('option', { name: `${pets} › ${vet}` })).toBeAttached()
  await add.getByRole('button', { name: 'Cancel' }).click()

  // Hiding the parent hides the subcategory too.
  await page.goto('/categories')
  await page.getByRole('button', { name: `Edit ${pets}` }).click()
  await page.getByRole('dialog', { name: `Edit ${pets}` }).getByRole('button', { name: 'Hide' }).click()
  const hidden = page.getByRole('region', { name: 'Hidden' })
  await expect(hidden.getByRole('button', { name: `Edit ${pets}` })).toBeVisible()
  await expect(hidden.getByRole('button', { name: `Edit ${vet}` })).toBeVisible()
  await page.goto('/transactions?add=EXPENSE')
  await expect(page.getByRole('dialog', { name: 'Add expense' }).getByLabel('Category').getByRole('option', { name: `${pets} › ${vet}` })).not.toBeAttached()

  await page.goto('/categories')
  await page.getByRole('button', { name: `Edit ${pets}` }).click()
  await page.getByRole('dialog', { name: `Edit ${pets}` }).getByRole('button', { name: 'Show' }).click()
  await expect(page.getByRole('region', { name: 'Hidden' })).toBeHidden()

  // A parent can't go while it has subcategories; deleting asks twice.
  await page.getByRole('button', { name: `Edit ${pets}` }).click()
  dialog = page.getByRole('dialog', { name: `Edit ${pets}` })
  await dialog.getByRole('button', { name: 'Delete' }).click()
  await dialog.getByRole('button', { name: 'Delete for good' }).click()
  await expect(dialog.getByRole('alert')).toHaveText('It has subcategories. Move or delete them first.')
  await dialog.getByRole('button', { name: 'Cancel' }).click()

  for (const name of [vet, pets]) {
    await page.getByRole('button', { name: `Edit ${name}` }).click()
    dialog = page.getByRole('dialog', { name: `Edit ${name}` })
    await dialog.getByRole('button', { name: 'Delete' }).click()
    await dialog.getByRole('button', { name: 'Delete for good' }).click()
    await expect(dialog).toBeHidden()
    await expect(page.getByRole('button', { name: `Edit ${name}` })).toBeHidden()
  }
})
