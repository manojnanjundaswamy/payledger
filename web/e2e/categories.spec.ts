import { expect, test } from '@playwright/test'
import { signIn, pick } from './helpers'

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
  await pick(dialog, 'Inside', pets)
  await dialog.getByRole('button', { name: 'Add category' }).click()
  await expect(dialog).toBeHidden()
  await expect(page.getByRole('button', { name: `Edit ${vet}` })).toBeVisible()

  // The transaction form offers it with its parent's name.
  await page.goto('/transactions?add=EXPENSE')
  const add = page.getByRole('dialog', { name: 'Add expense' })
  // The picker is searchable: typing the subcategory finds it.
  await add.getByLabel('Category', { exact: true }).click()
  await page.getByRole('textbox', { name: 'Search the list' }).fill(vet)
  await expect(page.getByRole('option', { name: `${pets} › ${vet}` })).toBeVisible()
  await page.keyboard.press('Escape')
  await add.getByRole('button', { name: 'Cancel' }).click()

  // Hiding the parent hides the subcategory too.
  await page.goto('/categories')
  await page.getByRole('button', { name: `Edit ${pets}` }).click()
  await page.getByRole('dialog', { name: `Edit ${pets}` }).getByRole('button', { name: 'Hide' }).click()
  const hidden = page.getByRole('region', { name: 'Hidden' })
  await expect(hidden.getByRole('button', { name: `Edit ${pets}` })).toBeVisible()
  await expect(hidden.getByRole('button', { name: `Edit ${vet}` })).toBeVisible()
  await page.goto('/transactions?add=EXPENSE')
  await page.getByRole('dialog', { name: 'Add expense' }).getByLabel('Category', { exact: true }).click()
  await page.getByRole('textbox', { name: 'Search the list' }).fill(vet)
  await expect(page.getByRole('option', { name: `${pets} › ${vet}` })).toHaveCount(0)
  await page.keyboard.press('Escape')

  await page.goto('/categories')
  await page.getByRole('button', { name: `Edit ${pets}` }).click()
  await page.getByRole('dialog', { name: `Edit ${pets}` }).getByRole('button', { name: 'Show' }).click()
  // Other runs may have hidden categories of their own; this one's are back in the list.
  await expect(page.getByRole('region', { name: 'Hidden' }).getByRole('button', { name: `Edit ${pets}` })).toBeHidden()
  await expect(page.getByRole('region', { name: 'Spending categories' }).getByRole('button', { name: `Edit ${vet}` })).toBeVisible()

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

test('merging a category moves its transactions to the other one and hides it', async ({ page }) => {
  const run = Date.now()
  const old = `E2E old ${run}`
  const bank = `E2E merge bank ${run}`

  await page.goto('/categories')
  await page.getByRole('button', { name: 'Add category' }).click()
  let dialog = page.getByRole('dialog', { name: 'Add a spending category' })
  await dialog.getByLabel('Name').fill(old)
  await dialog.getByRole('button', { name: 'Add category' }).click()
  await expect(dialog).toBeHidden()
  await page.goto('/accounts')
  await page.getByRole('button', { name: 'Add account' }).first().click()
  dialog = page.getByRole('dialog', { name: 'Add an account' })
  await dialog.getByLabel('Name').fill(bank)
  await dialog.getByRole('button', { name: 'Add account' }).click()
  await expect(dialog).toBeHidden()
  await page.goto('/transactions?add=EXPENSE')
  const add = page.getByRole('dialog', { name: 'Add expense' })
  await add.getByLabel('Amount').fill('75')
  await pick(add, 'Category', old)
  await pick(add, 'Paid from', bank)
  await add.getByLabel('Description').fill('Merge me')
  await add.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(add).toBeHidden()

  await page.goto('/categories')
  await page.getByRole('button', { name: `Edit ${old}` }).click()
  dialog = page.getByRole('dialog', { name: `Edit ${old}` })
  await pick(dialog, 'Merge into another category', 'Groceries')
  await dialog.getByRole('button', { name: 'Merge', exact: true }).click()
  await expect(dialog).toBeHidden()
  await expect(page.getByRole('region', { name: 'Hidden' }).getByRole('button', { name: `Edit ${old}` })).toBeVisible()

  await page.goto('/transactions')
  await pick(page, 'Filter by account', bank)
  const row = page.getByRole('button', { name: 'Edit Merge me' })
  await expect(row).toContainText(`Groceries · ${bank}`)

  // Clean up.
  await row.click()
  await page.getByRole('dialog', { name: 'Edit expense' }).getByRole('button', { name: 'Delete' }).click()
  await expect(page.getByText('Nothing here yet')).toBeVisible()
  await page.goto('/accounts')
  await page.getByRole('button', { name: `Edit ${bank}` }).click()
  await page.getByRole('dialog', { name: `Edit ${bank}` }).getByRole('button', { name: 'Archive' }).click()
  await expect(page.getByRole('button', { name: `Edit ${bank}` })).toBeHidden()
})
