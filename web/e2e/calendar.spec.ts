import { expect, test } from '@playwright/test'
import { callApi, signIn } from './helpers'

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

test('transactions show on a month calendar; a day opens its list or adds on that date', async ({ page }) => {
  const run = Date.now()
  const me = await callApi<{ spaces: { id: string }[] }>(page, 'GET', '/api/core/me')
  const base = `/api/core/spaces/${me.spaces[0]!.id}`
  const account = await callApi<{ id: string }>(page, 'POST', `${base}/accounts`, { kind: 'CASH', name: `E2E cal ${run}`, openingBalance: 0, openingDate: '2026-01-01' })
  const categories = await callApi<{ id: string; name: string }[]>(page, 'GET', `${base}/categories`)
  const today = iso(new Date())
  const t = await callApi<{ id: string; version: number }>(page, 'POST', `${base}/transactions`, {
    kind: 'EXPENSE', amount: 12_300, accountId: account.id, categoryId: categories.find((c) => c.name === 'Groceries')!.id, occurredOn: today, description: `E2E cal ${run}`,
  })

  await page.goto(`/transactions?account=${account.id}`)
  await page.getByRole('radio', { name: 'Calendar' }).click()
  await expect(page).toHaveURL(/view=calendar/)
  const day = page.getByTestId(`day-${today}`)
  await expect(day).toContainText(`E2E cal ${run}`)
  await expect(page.getByTestId('calendar-totals')).toContainText('₹123.00 spent')

  // A chip opens the transaction.
  await day.getByRole('button', { name: `Open E2E cal ${run}` }).click()
  await expect(page.getByRole('dialog', { name: 'Edit expense' }).getByLabel('Description')).toHaveValue(`E2E cal ${run}`)
  await page.keyboard.press('Escape')

  // The rest of the day: its list, or add on that date.
  await day.click({ position: { x: 4, y: 4 } })
  await page.getByRole('menu').getByRole('menuitem', { name: 'Add income on this day' }).click()
  await expect(page.getByRole('dialog', { name: 'Add income' }).getByLabel('Date')).toHaveValue(today)
  await page.getByRole('dialog', { name: 'Add income' }).getByRole('button', { name: 'Cancel' }).click()
  await day.click({ position: { x: 4, y: 4 } })
  await page.getByRole('menu').getByRole('menuitem', { name: "See this day's transactions" }).click()
  await expect(page).toHaveURL(new RegExp(`from=.*${today}`))
  await expect(page.getByRole('button', { name: `Edit E2E cal ${run}` })).toBeVisible()

  // Months move back and forth.
  await page.goto(`/transactions?view=calendar&month=${today.slice(0, 7)}`)
  const label = await page.getByTestId('calendar-month').textContent()
  await page.getByRole('button', { name: 'Previous month' }).click()
  await expect(page.getByTestId('calendar-month')).not.toHaveText(label!)
  await page.getByRole('button', { name: 'Today' }).click()
  await expect(page.getByTestId('calendar-month')).toHaveText(label!)

  await callApi(page, 'DELETE', `${base}/transactions/${t.id}?version=${t.version}`)
  await callApi(page, 'PATCH', `${base}/accounts/${account.id}`, { archived: true })
})

test('a day on any calendar can start a bill due that day', async ({ page }) => {
  const day = iso(new Date(Date.now() + 5 * 86_400_000))
  await page.goto(`/plan?due=${day}`)
  const dialog = page.getByRole('dialog', { name: 'Add to your plan' })
  await expect(dialog.getByLabel('Next due')).toHaveValue(day)
  await expect(page).not.toHaveURL(/due=/)
  await dialog.getByRole('button', { name: 'Cancel' }).click()
})
