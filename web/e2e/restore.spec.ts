import { expect, test } from '@playwright/test'
import { signIn } from './helpers'

test('your download can be restored, and never on top of existing data', async ({ page }) => {
  await signIn(page)
  const file = await page.evaluate(async () => (await fetch('/api/core/me/export')).text())
  expect(JSON.parse(file).format).toBe('payledger-export')

  await page.goto('/settings?tab=account')
  await page.getByRole('button', { name: 'Restore my data' }).click()
  const dialog = page.getByRole('dialog', { name: 'Restore your data' })
  await dialog.getByLabel('Your PayLedger download').setInputFiles({ name: 'payledger-my-data.json', mimeType: 'application/json', buffer: Buffer.from(file) })
  await dialog.getByRole('button', { name: 'Restore', exact: true }).click()
  await expect(dialog.getByRole('alert')).toContainText('no accounts or entries yet')
})
