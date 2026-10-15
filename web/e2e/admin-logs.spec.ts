import { readFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'
import { signIn } from './helpers'

test.beforeEach(async ({ page }) => {
  await signIn(page)
})

test('every request is on record: find one by its reference, open it, export the log', async ({ page }) => {
  // Make a failing request to look for (a space that doesn't exist).
  const failed = await page.request.get(`/api/core/spaces/${crypto.randomUUID()}/accounts`)
  expect(failed.status()).toBe(404)
  const reference = failed.headers()['x-request-id']!
  expect((await failed.json()).requestId).toBe(reference)

  await page.goto('/admin')
  await page.getByTestId('health-errors').click()
  await expect(page).toHaveURL(/tab=requests/)
  await expect(page.getByRole('radio', { name: 'Errors' })).toBeChecked()

  await page.getByLabel('Search requests').fill(reference)
  await expect(page.getByTestId('requests-total')).toHaveText('1 requests')
  await page.getByTestId('requests').getByRole('button', { name: /404/ }).click()
  const details = page.getByTestId('request-details')
  await expect(details).toContainText(reference)
  await expect(details).toContainText('space-not-found')
  await expect(details).toContainText('demo@payledger.local')
  // To the millisecond.
  await expect(details).toContainText(/\d{2}:\d{2}:\d{2}\.\d{3}/)

  const download = page.waitForEvent('download')
  await page.getByTestId('export-requests').click()
  const file = await (await download).path()
  const csv = readFileSync(file!, 'utf-8')
  expect(csv).toContain('request_id')
  expect(csv).toContain(reference)
})

test('activity and audit rows open up, and each log exports', async ({ page }) => {
  await page.goto('/admin?tab=activity')
  await page.getByTestId('activity-list').getByRole('button').first().click()
  await expect(page.getByTestId('activity-details')).toContainText('When')
  await expect(page.getByTestId('activity-details')).toContainText('What happened')
  for (const [tab, kind] of [['activity', 'activity'], ['audit', 'audit'], ['emails', 'emails'], ['users', 'users']] as const) {
    await page.goto(`/admin?tab=${tab}`)
    const download = page.waitForEvent('download')
    await page.getByTestId(`export-${kind}`).click()
    expect((await download).suggestedFilename()).toMatch(new RegExp(`^payledger-${kind}-.*\\.csv$`))
  }
  await page.goto('/admin?tab=audit')
  await expect(page.getByText('exported the user list').first()).toBeVisible()
})
