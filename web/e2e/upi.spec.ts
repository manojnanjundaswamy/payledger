import { expect, test } from '@playwright/test'
import { signIn, signUp } from './helpers'

// A fresh E2E user sets a UPI id and pays for something shared with the demo user; the demo user then sees a one-tap
// "Pay via UPI" for exactly what they owe. The demo user's own profile is never changed.
test('a friend with a UPI id gets paid in one tap', async ({ browser, request }) => {
  test.setTimeout(120_000)
  const run = Date.now()
  const upi = `e2e${run}@okhdfcbank`
  const groupName = `E2E upi ${run}`

  const raviContext = await browser.newContext()
  const ravi = await raviContext.newPage()
  await signUp(ravi, request, `e2e-upi-${run}@example.com`)
  await ravi.goto('/settings?tab=profile')
  await ravi.getByLabel('UPI ID').fill('not a upi id')
  await expect(ravi.getByText("That doesn't look like a UPI ID")).toBeVisible()
  await ravi.getByLabel('UPI ID').fill(upi)
  await ravi.getByLabel('UPI ID').press('Enter')
  await expect(ravi.getByText('UPI ID saved')).toBeVisible()

  await ravi.goto('/groups')
  await ravi.getByRole('button', { name: 'New group' }).first().click()
  const create = ravi.getByRole('dialog', { name: 'New group' })
  await create.getByLabel('Name').fill(groupName)
  await create.getByRole('button', { name: 'Create group' }).click()
  const add = ravi.getByRole('region', { name: 'Add someone' })
  await add.getByLabel('Name').fill('Demo')
  await add.getByLabel('Email (optional)').fill('demo@payledger.local')
  await add.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(ravi.getByTestId('member-Demo')).toBeVisible()
  await ravi.getByRole('button', { name: 'Add expense' }).click()
  const expense = ravi.getByRole('dialog', { name: 'Add a shared expense' })
  await expense.getByLabel('What for').fill(`E2E dinner ${run}`)
  await expense.getByLabel('Amount').fill('1,000')
  await expense.getByRole('button', { name: 'Add expense' }).click()
  await expect(expense).toBeHidden()

  const demoContext = await browser.newContext()
  const demo = await demoContext.newPage()
  await signIn(demo)
  await demo.goto('/groups')
  await demo.getByRole('link', { name: new RegExp(groupName) }).click()
  const pay = demo.getByRole('link', { name: /Pay .* via UPI/ })
  await expect(pay).toBeVisible({ timeout: 20_000 })
  const href = await pay.getAttribute('href')
  expect(href).toContain(`upi://pay?pa=${encodeURIComponent(upi)}`)
  expect(href).toContain('am=500.00')
  expect(href).toContain('cu=INR')

  // Clean up: delete the expense (the E2E group stays, empty).
  await ravi.getByRole('button', { name: `Edit E2E dinner ${run}` }).click()
  await ravi.getByRole('dialog', { name: 'Edit expense' }).getByRole('button', { name: 'Delete' }).click()
  await raviContext.close()
  await demoContext.close()
})
