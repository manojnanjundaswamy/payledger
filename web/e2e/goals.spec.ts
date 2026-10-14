import { expect, test } from '@playwright/test'
import { callApi, pick, signIn } from './helpers'

test('a savings goal says what it needs each month, takes money, and can be archived and deleted', async ({ page }) => {
  await signIn(page)
  const run = Date.now()
  const name = `E2E goal ${run}`
  const by = new Date()
  by.setMonth(by.getMonth() + 9)
  by.setDate(28)
  const byIso = `${by.getFullYear()}-${String(by.getMonth() + 1).padStart(2, '0')}-28`

  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Goals' }).click()
  await page.getByRole('button', { name: 'New goal' }).first().click()
  const dialog = page.getByRole('dialog', { name: 'New goal' })
  await dialog.getByLabel('What for').fill(name)
  await dialog.locator('label').filter({ hasText: '🏖' }).click()
  await dialog.getByLabel('Amount').fill('50,000')
  await dialog.getByLabel('By (optional)').fill(byIso)
  await dialog.getByRole('button', { name: 'Add goal' }).click()
  await expect(dialog).toBeHidden()

  // Ten months including this one: ₹5,000 a month.
  const card = page.getByTestId(`goal-${name}`)
  await expect(card.getByTestId('goal-advice')).toContainText('Put aside ₹5,000 a month')
  await expect(card.getByTestId('goal-status')).toHaveText('On track')

  // Add money: the monthly amount drops.
  await card.getByRole('button', { name: 'Add money' }).click()
  const add = page.getByRole('dialog', { name: `Add money to ${name}` })
  await expect(add.getByLabel('Amount')).toHaveValue('5000')
  await add.getByLabel('Amount').fill('20,000')
  await add.getByLabel('Note (optional)').fill('Bonus')
  await add.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(card.getByTestId('goal-saved')).toHaveText('₹20,000')
  await expect(card.getByTestId('goal-advice')).toContainText('Put aside ₹3,000 a month')

  // Archive, find it among archived, delete it.
  await card.getByRole('button', { name: `Archive ${name}` }).click()
  await expect(card).toBeHidden()
  await page.getByRole('switch', { name: 'Show archived goals' }).click()
  await expect(card).toContainText('Archived')
  await card.getByRole('button', { name: `Delete ${name}` }).click()
  await expect(card).toBeHidden()
})

test('a transfer marked for a goal counts towards it, its entries are one click away, and it can go on the plan', async ({ page }) => {
  await signIn(page)
  const run = Date.now()
  const me = await callApi<{ spaces: { id: string }[] }>(page, 'GET', '/api/core/me')
  const base = `/api/core/spaces/${me.spaces[0]!.id}`
  const from = await callApi<{ id: string }>(page, 'POST', `${base}/accounts`, { kind: 'BANK', name: `E2E goal from ${run}`, openingBalance: 0, openingDate: '2026-01-01' })
  const to = await callApi<{ id: string }>(page, 'POST', `${base}/accounts`, { kind: 'BANK', name: `E2E goal pot ${run}`, openingBalance: 0, openingDate: '2026-01-01' })
  const by = new Date()
  by.setMonth(by.getMonth() + 5)
  const name = `E2E trip ${run}`
  const goal = await callApi<{ id: string; version: number }>(page, 'POST', `${base}/goals`,
    { name, target: 6_000_000, targetDate: `${by.getFullYear()}-${String(by.getMonth() + 1).padStart(2, '0')}-28` })

  await page.goto('/goals')
  const card = page.getByTestId(`goal-${name}`)
  await card.getByRole('button', { name: 'Record a transfer' }).click()
  const add = page.getByRole('dialog', { name: 'Add transfer' })
  await expect(add.getByLabel('For a goal (optional)')).toContainText(name)
  await add.getByLabel('Amount').fill('2,000')
  await pick(add, 'From', `E2E goal from ${run}`)
  await pick(add, 'To', `E2E goal pot ${run}`)
  await add.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(add).toBeHidden()
  await expect(card.getByTestId('goal-entries')).toContainText('₹2,000 from entries')
  await expect(card.getByTestId('goal-saved')).toHaveText('₹2,000')

  // Its transactions, filtered by the goal.
  await card.getByRole('link', { name: 'Its transactions' }).click()
  await expect(page.getByTestId('filter-chip').filter({ hasText: `For 🎯 ${name}` })).toBeVisible()
  await expect(page.getByRole('button', { name: `Edit Saving for ${name}` })).toBeVisible()

  // On the plan: a monthly saving for it, filled in (cancelled here).
  await page.goto('/goals')
  await card.getByRole('button', { name: 'Add to my plan' }).click()
  const plan = page.getByRole('dialog', { name: 'Add to your plan' })
  await expect(plan.getByLabel('Name')).toHaveValue(`Saving for ${name}`)
  await plan.getByRole('button', { name: 'Cancel' }).click()

  // Clean up.
  const left = await callApi<{ items: { id: string; version: number }[] }>(page, 'GET', `${base}/transactions?accountId=${from.id}`)
  for (const t of left.items) await callApi(page, 'DELETE', `${base}/transactions/${t.id}?version=${t.version}`)
  await callApi(page, 'DELETE', `${base}/goals/${goal.id}`)
  for (const a of [from, to]) await callApi(page, 'PATCH', `${base}/accounts/${a.id}`, { archived: true })
})
