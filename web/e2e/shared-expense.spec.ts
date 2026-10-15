import { expect, test } from '@playwright/test'
import { callApi, pick, signIn } from './helpers'

test('an expense ticked as shared becomes the group\'s, split equally', async ({ page }) => {
  await signIn(page)
  const run = Date.now()
  const group = await callApi<{ id: string }>(page, 'POST', '/api/core/groups', { name: `E2E flat ${run}` })
  await callApi(page, 'POST', `/api/core/groups/${group.id}/members`, { name: 'Ravi' })

  await page.goto('/transactions?add=EXPENSE')
  const add = page.getByRole('dialog', { name: 'Add expense' })
  await add.getByLabel('Amount').fill('900')
  await pick(add, 'Category', 'Groceries')
  await add.getByLabel('Description').fill(`E2E veggies ${run}`)
  await add.getByRole('switch', { name: 'Shared expense' }).click()
  await pick(add, 'Group', `👥 E2E flat ${run}`)
  await expect(add.getByTestId('shared-summary')).toContainText('₹450.00 each')
  await add.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(add).toBeHidden()

  const activity = await callApi<{ expenses: { description: string; amount: number; parts: { share: number }[] }[] }>(page, 'GET', `/api/core/groups/${group.id}/activity`)
  expect(activity.expenses[0]!.description).toBe(`E2E veggies ${run}`)
  expect(activity.expenses[0]!.parts.map((p) => p.share)).toEqual([45000, 45000])
})
