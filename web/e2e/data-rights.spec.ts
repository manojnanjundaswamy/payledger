import { readFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'
import { signUp } from './helpers'

// A fresh E2E user (never the demo user): download everything, then delete the account for good.
test('you can download all your data and delete your account', async ({ page, request }) => {
  test.setTimeout(120_000)
  const email = `e2e-delete-${Date.now()}@example.com`
  await signUp(page, request, email)

  await page.goto('/settings?tab=account')
  const download = page.waitForEvent('download')
  await page.getByRole('link', { name: 'Download all my data' }).click()
  const file = await download
  expect(file.suggestedFilename()).toMatch(/^payledger-my-data-\d{4}-\d{2}-\d{2}\.json$/)
  const data = JSON.parse(readFileSync(await file.path(), 'utf-8'))
  expect(data.profile[0].email).toBe(email)
  expect(Array.isArray(data.transactions)).toBe(true)

  await page.getByRole('button', { name: 'Delete my account' }).click()
  const dialog = page.getByRole('dialog', { name: 'Delete your account?' })
  const go = dialog.getByRole('button', { name: 'Delete for good' })
  await expect(go).toBeDisabled()
  await dialog.getByLabel('Type your email to confirm').fill(email.toUpperCase())
  await go.click()
  // Signed out: back where signing in starts.
  await expect(page).not.toHaveURL(/\/settings/, { timeout: 20_000 })
  await expect(page.getByRole('link', { name: 'Sign in' }).first()).toBeVisible({ timeout: 20_000 })
})
