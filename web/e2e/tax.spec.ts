import { expect, test } from '@playwright/test'
import { callApi, signIn } from './helpers'

test('an entry tagged 80C counts on the tax page, against the limit, and the CA export has it', async ({ page }) => {
  await signIn(page)
  const run = Date.now()
  const me = await callApi<{ spaces: { id: string }[] }>(page, 'GET', '/api/core/me')
  const base = `/api/core/spaces/${me.spaces[0]!.id}`
  const account = await callApi<{ id: string }>(page, 'POST', `${base}/accounts`, { kind: 'BANK', name: `E2E tax bank ${run}`, openingBalance: 0 })
  const categories = await callApi<{ id: string; kind: string; name: string }[]>(page, 'GET', `${base}/categories`)
  const other = categories.find((c) => c.kind === 'EXPENSE' && c.name === 'Other')!
  const d = new Date()
  const today = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  await callApi(page, 'POST', `${base}/transactions`, {
    kind: 'EXPENSE', amount: 1_234_500, accountId: account.id, categoryId: other.id, occurredOn: today, description: `E2E ELSS ${run}`, tags: ['80C'],
  })

  await page.goto('/reports')
  await page.getByRole('link', { name: 'Tax (80C, 80D…)' }).click()
  const section = page.getByTestId('tax-80C')
  await expect(section).toContainText(/room of ₹1,50,000|limit reached/)
  await section.getByRole('button').click()
  await expect(section).toContainText(`E2E ELSS ${run}`)
  await expect(section).toContainText('tagged')

  const csv = await page.evaluate(async (url) => (await fetch(url)).text(), `${base}/tax/export`)
  expect(csv).toContain(`E2E ELSS ${run}`)
})
