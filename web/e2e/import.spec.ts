import { readFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'
import { signIn } from './helpers'

test.beforeEach(async ({ page }) => {
  await signIn(page)
})

test('a CSV is checked row by row, new names are mapped, and the import can be undone', async ({ page }) => {
  // The demo user is shared by every run: this run's own account and category names keep it apart.
  const run = Date.now()
  const bank = `E2E import bank ${run}`
  const pets = `E2E pets ${run}`
  const file = `october-${run}.csv`
  const csv = [
    'Date,Amount,Type,Account,Category,Description,Payment mode,External ID',
    `05-10-2026,486,expense,${bank},Groceries,BigBasket,UPI,E2E-${run}-1`,
    `06/10/26,"1,250.50",expense,${bank},${pets},Vet visit,card,E2E-${run}-2`,
    `07-10-2026,"1,00,000",income,${bank},Salary,October salary,,E2E-${run}-3`,
    `31-02-2026,99,expense,${bank},Groceries,Bad date,,E2E-${run}-4`,
  ].join('\r\n')
  const upload = () => page.getByLabel('CSV file').setInputFiles({ name: file, mimeType: 'text/csv', buffer: Buffer.from(csv) })

  await page.goto('/transactions')
  await page.getByRole('link', { name: 'Import' }).click()
  await expect(page.getByRole('link', { name: 'Download the template' })).toBeVisible()
  await upload()

  // Nothing matches the new account yet, so every row needs it; the bad date is reported either way.
  await expect(page.getByTestId('import-with-problems')).toHaveText('4')
  await page.getByLabel(`Account for ${bank}`).selectOption({ label: 'New bank account' })
  await expect(page.getByTestId('import-ready-to-import')).toHaveText('2')
  await page.getByLabel(`Category for ${pets}`).selectOption({ label: 'Create it' })
  await expect(page.getByTestId('import-ready-to-import')).toHaveText('3')
  await expect(page.getByTestId('import-with-problems')).toHaveText('1')
  await expect(page.getByRole('region', { name: 'Problems' })).toContainText('Row 5')
  await expect(page.getByRole('region', { name: 'Problems' })).toContainText('date: not a date')
  const report = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Error report' }).click()
  expect((await report).suggestedFilename()).toBe(`october-${run}-problems.csv`)

  await page.getByRole('button', { name: 'Import 3 rows' }).click()
  await expect(page).toHaveURL(/\/transactions$/)
  await page.getByLabel('Filter by account').selectOption({ label: bank })
  await expect(page.getByRole('button', { name: 'Edit Vet visit' })).toContainText('-₹1,250.50')
  await expect(page.getByRole('button', { name: 'Edit Vet visit' })).toContainText(`${pets} · ${bank}`)
  await expect(page.getByRole('button', { name: 'Edit October salary' })).toContainText('+₹1,00,000.00')

  // The same file again: everything is already there.
  await page.goto('/import')
  await upload()
  await expect(page.getByTestId('import-already-there')).toHaveText('3')
  await expect(page.getByRole('button', { name: 'Import 0 rows' })).toBeDisabled()

  // Undo the import: its transactions go.
  const past = page.getByTestId(`import-${file}`)
  await past.getByRole('button', { name: 'Undo' }).click()
  await past.getByRole('button', { name: 'Delete 3' }).click()
  await expect(past).toContainText('Undone')
  await page.goto('/transactions')
  await page.getByLabel('Filter by account').selectOption({ label: bank })
  await expect(page.getByText('Nothing here yet')).toBeVisible()

  // The template downloads through the gateway with the session, pre-filled with this user's accounts.
  await page.goto('/import')
  const download = page.waitForEvent('download')
  await page.getByRole('link', { name: 'Download the template' }).click()
  const template = await download
  expect(template.suggestedFilename()).toBe('payledger-template.csv')
  expect(readFileSync(await template.path(), 'utf-8')).toContain('date,amount,type,account,category')

  // Clean up: hide the category (its history stays), archive the account.
  await page.goto('/categories')
  await page.getByRole('button', { name: `Edit ${pets}` }).click()
  await page.getByRole('dialog', { name: `Edit ${pets}` }).getByRole('button', { name: 'Hide' }).click()
  await page.goto('/accounts')
  await page.getByRole('button', { name: `Edit ${bank}` }).click()
  await page.getByRole('dialog', { name: `Edit ${bank}` }).getByRole('button', { name: 'Archive' }).click()
  await expect(page.getByRole('button', { name: `Edit ${bank}` })).toBeHidden()
})
