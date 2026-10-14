import { expect, test } from '@playwright/test'
import { callApi, pick, signIn } from './helpers'

// The demo user's account is shared: this run's own words (zq<run>) and account keep it apart, and it's all removed.
test('a category is guessed from what you chose before, and "always use this" makes a rule', async ({ page }) => {
  await signIn(page)
  const run = Date.now()
  const word = `zq${run}`
  const me = await callApi<{ spaces: { id: string }[] }>(page, 'GET', '/api/core/me')
  const base = `/api/core/spaces/${me.spaces[0]!.id}`
  const categories = await callApi<{ id: string; name: string }[]>(page, 'GET', `${base}/categories`)
  const groceries = categories.find((c) => c.name === 'Groceries')!.id
  const account = await callApi<{ id: string }>(page, 'POST', `${base}/accounts`, { kind: 'CASH', name: `E2E smart ${run}`, openingBalance: 0, openingDate: '2026-01-01' })
  const yesterday = new Date(Date.now() - 86_400_000).toISOString().slice(0, 10)
  await callApi(page, 'POST', `${base}/transactions`, { kind: 'EXPENSE', amount: 9_900, accountId: account.id, categoryId: groceries, occurredOn: yesterday, description: `Zepto ${word}` })

  // History: the same description gets the same category, and says why.
  await page.goto(`/transactions?add=EXPENSE&account=${account.id}`)
  let add = page.getByRole('dialog', { name: 'Add expense' })
  await add.getByLabel('Amount').fill('120')
  await add.getByLabel('Description').fill(`Zepto ${word}`)
  await expect(add.getByLabel('Category', { exact: true })).toHaveText('Groceries')
  await expect(add.getByTestId('category-guess')).toHaveText(`You chose this before for “Zepto ${word}”`)
  await add.getByRole('button', { name: 'Cancel' }).click()

  // A new place: choose its category once and ask to always use it.
  await page.goto(`/transactions?add=EXPENSE&account=${account.id}`)
  add = page.getByRole('dialog', { name: 'Add expense' })
  await add.getByLabel('Amount').fill('80')
  await add.getByLabel('Description').fill(`${word} cafe`)
  await pick(add, 'Category', 'Eating out')
  await add.getByLabel(`Always use this for “${word}”`).check()
  await add.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(page.getByText(`“${word}” goes to Eating out from now on.`)).toBeVisible()

  // Next time, the rule fills it in.
  await page.goto(`/transactions?add=EXPENSE&account=${account.id}`)
  add = page.getByRole('dialog', { name: 'Add expense' })
  await add.getByLabel('Description').fill(`${word} cafe again`)
  await expect(add.getByLabel('Category', { exact: true })).toHaveText('Eating out')
  await expect(add.getByTestId('category-guess')).toHaveText(`From your rule for “${word}”`)
  await add.getByRole('button', { name: 'Cancel' }).click()

  // The rule is listed; applying it moves the old Zepto entry; then it's deleted.
  await page.goto('/categories')
  const rule = page.getByTestId(`rule-${word}`)
  await expect(rule).toContainText('Eating out')
  await rule.getByRole('button', { name: 'Apply to past' }).click()
  await expect(page.getByText('1 past transaction moved')).toBeVisible()
  await rule.getByRole('button', { name: `Delete rule ${word}` }).click()
  await expect(rule).toBeHidden()

  // Clean up.
  const left = await callApi<{ items: { id: string; version: number }[] }>(page, 'GET', `${base}/transactions?accountId=${account.id}`)
  for (const t of left.items) await callApi(page, 'DELETE', `${base}/transactions/${t.id}?version=${t.version}`)
  await callApi(page, 'PATCH', `${base}/accounts/${account.id}`, { archived: true })
})
