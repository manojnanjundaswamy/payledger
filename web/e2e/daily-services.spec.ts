import { expect, test, type Page } from '@playwright/test'
import { callApi, signUp } from './helpers'

async function setUp(page: Page) {
  const me = await callApi<{ spaces: { id: string }[] }>(page, 'GET', '/api/core/me')
  const space = me.spaces[0]!.id
  await callApi(page, 'POST', `/api/core/spaces/${space}/accounts`, { kind: 'BANK', name: 'E2E Bank' })
  return space
}

for (const tag of ['', ' @phone']) {
  test(`milk: tap a missed day, the bill follows, pay it as one expense${tag}`, async ({ page, request }) => {
    await signUp(page, request, `e2e-services-${tag ? 'p' : 'd'}-${Date.now()}@example.com`)
    const space = await setUp(page)
    await page.goto('/daily-services')
    await page.getByRole('button', { name: 'Milk' }).click()
    const dialog = page.getByRole('dialog', { name: 'Add a service' })
    await dialog.getByLabel('A normal day costs (₹)').fill('60')
    await dialog.getByLabel('Started').fill(new Date().toISOString().slice(0, 8) + '01')
    await dialog.getByRole('button', { name: 'Add service' }).click()

    const card = page.getByTestId('service-Milk')
    const days = new Date(new Date().getFullYear(), new Date().getMonth() + 1, 0).getDate()
    await expect(card.getByTestId('service-bill')).toHaveText(`₹${(60 * days).toLocaleString('en-IN')}.00`)
    await card.getByRole('button', { name: /^1: normal$/ }).click()
    await expect(card.getByRole('button', { name: /^1: missed$/ })).toBeVisible()
    await expect(card.getByTestId('service-bill')).toHaveText(`₹${(60 * (days - 1)).toLocaleString('en-IN')}.00`)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)

    await card.getByRole('button', { name: /^Pay / }).click()
    const pay = page.getByRole('dialog', { name: 'Pay Milk' })
    await pay.getByLabel('Paid from').click()
    await page.getByRole('option', { name: /E2E Bank/ }).click()
    await pay.getByRole('button', { name: 'Record payment' }).click()
    await expect(card.getByText('Paid', { exact: true })).toBeVisible()
    const list = await callApi<{ items: { description: string; amount: number }[] }>(page, 'GET', `/api/core/spaces/${space}/transactions?q=Milk`)
    expect(list.items.some((t) => t.description.startsWith('Milk:') && t.amount === 6000 * (days - 1))).toBe(true)
  })
}
