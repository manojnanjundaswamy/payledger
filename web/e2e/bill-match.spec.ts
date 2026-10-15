import { expect, test } from '@playwright/test'
import { callApi, pick, signIn } from './helpers'

// The owner's report: a ₹35,000 insurance on the plan, the premium entered as an expense in Insurance, and the plan
// still said it was due. Now the expense pays it; unlinked, it's suggested; one tap links it again.
test('entering a bill’s expense yourself marks it paid, and a suggestion links it back', async ({ page }) => {
  await signIn(page)
  const run = Date.now()
  const bill = `E2E insurance ${run}`
  const me = await callApi<{ spaces: { id: string }[] }>(page, 'GET', '/api/core/me')
  const base = `/api/core/spaces/${me.spaces[0]!.id}`
  const categories = await callApi<{ id: string; name: string }[]>(page, 'GET', `${base}/categories`)
  const insurance = categories.find((c) => c.name === 'Insurance')!.id
  const account = await callApi<{ id: string }>(page, 'POST', `${base}/accounts`, { kind: 'BANK', name: `E2E match ${run}`, openingBalance: 0, openingDate: '2026-01-01' })
  const due = new Date(Date.now() + 3 * 86_400_000)
  const dueIso = `${due.getFullYear()}-${String(due.getMonth() + 1).padStart(2, '0')}-${String(due.getDate()).padStart(2, '0')}`
  const commitment = await callApi<{ id: string }>(page, 'POST', `${base}/commitments`, {
    kind: 'BILL', name: bill, amount: 3_500_000, accountId: account.id, categoryId: insurance, toAccountId: null, everyMonths: 12, firstDue: dueIso,
  })

  // The premium, entered as an ordinary expense.
  await page.goto(`/transactions?add=EXPENSE&account=${account.id}`)
  const add = page.getByRole('dialog', { name: 'Add expense' })
  await add.getByLabel('Amount').fill('35,000')
  await add.getByLabel('Description').fill(`LIC premium ${run}`)
  await pick(add, 'Category', 'Insurance')
  await add.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(page.getByText(`Counted as paying ${bill}`)).toBeVisible()

  // The plan shows it paid; the badge opens the payment.
  await page.goto('/plan')
  const row = page.getByTestId(`due-${bill}`)
  await expect(row.getByText('Paid', { exact: true }).first()).toBeVisible()
  await row.getByRole('button', { name: `Unlink the payment of ${bill}` }).click()
  await page.getByRole('button', { name: /Wrong transaction/ }).click()
  await expect(row.getByRole('button', { name: 'Mark paid' })).toBeVisible()

  // Unlinked, it's suggested; one tap links it again.
  const suggestion = page.getByTestId(`suggestion-${bill}`)
  await expect(suggestion).toContainText(`Looks paid: LIC premium ${run}`)
  await suggestion.getByRole('button', { name: "That's it" }).click()
  await expect(row.getByText('Paid', { exact: true }).first()).toBeVisible()
  await row.getByRole('link', { name: `Open the payment of ${bill}` }).click()
  await expect(page.getByRole('dialog', { name: 'Edit expense' }).getByLabel('Description')).toHaveValue(`LIC premium ${run}`)
  await page.goto('/plan')
  // Paid by mistake: unlink and delete the expense; nothing is left to suggest.
  await row.getByRole('button', { name: `Unlink the payment of ${bill}` }).click()
  await page.getByRole('button', { name: /Not paid: unlink and delete/ }).click()
  await expect(row.getByRole('button', { name: 'Mark paid' })).toBeVisible()
  await expect(suggestion).toBeHidden()
  const remaining = await callApi<{ items: { description: string }[] }>(page, 'GET', `${base}/transactions?accountId=${account.id}`)
  expect(remaining.items.map((t) => t.description)).not.toContain(`LIC premium ${run}`)
  // Clean up.
  await callApi(page, 'DELETE', `${base}/commitments/${commitment.id}`)
  const left = await callApi<{ items: { id: string; version: number }[] }>(page, 'GET', `${base}/transactions?accountId=${account.id}`)
  for (const t of left.items) await callApi(page, 'DELETE', `${base}/transactions/${t.id}?version=${t.version}`)
  await callApi(page, 'PATCH', `${base}/accounts/${account.id}`, { archived: true })
})
