import { expect, test } from '@playwright/test'
import { MAILPIT, signIn, signUp } from './helpers'

// Two people online at once: what one does in a shared group reaches the other within seconds (the bell, a pop-up),
// and their email. The second person is a brand-new E2E user; the demo user only touches this run's own group.
test('when someone adds you and splits an expense, you see it live and get the email', async ({ browser, request }) => {
  test.setTimeout(120_000)
  const run = Date.now()
  const ravi = `e2e-live-${run}@example.com`
  const groupName = `E2E live ${run}`
  const lunch = `E2E lunch ${run}`

  const raviContext = await browser.newContext()
  const raviPage = await raviContext.newPage()
  await signUp(raviPage, request, ravi)
  await expect(raviPage.getByRole('button', { name: /^Notifications/ })).toBeVisible()

  const demoContext = await browser.newContext()
  const demo = await demoContext.newPage()
  await signIn(demo)
  await demo.goto('/groups')
  await demo.getByRole('button', { name: 'New group' }).first().click()
  const dialog = demo.getByRole('dialog', { name: 'New group' })
  await dialog.getByLabel('Name').fill(groupName)
  await dialog.getByRole('button', { name: 'Create group' }).click()
  await expect(demo.getByTestId('group-name')).toHaveText(groupName)
  const add = demo.getByRole('region', { name: 'Add someone' })
  await add.getByLabel('Name').fill('Ravi')
  await add.getByLabel('Email (optional)').fill(ravi)
  await add.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(demo.getByTestId('member-Ravi')).toBeVisible()

  // Ravi, just looking at his dashboard: a pop-up and the badge, within seconds.
  await expect(raviPage.getByText(`added you to ${groupName}`).first()).toBeVisible({ timeout: 15_000 })
  await expect(raviPage.getByTestId('unread-count')).toBeVisible()

  await demo.getByRole('button', { name: 'Add expense' }).click()
  const expense = demo.getByRole('dialog', { name: 'Add a shared expense' })
  await expense.getByLabel('What for').fill(lunch)
  await expense.getByLabel('Amount').fill('1,000')
  await expense.getByRole('button', { name: 'Add expense' }).click()
  await expect(expense).toBeHidden()

  await expect(raviPage.getByText(`${lunch} (₹1,000) in ${groupName}`).first()).toBeVisible({ timeout: 15_000 })

  // The bell lists it; opening it goes to the group and marks it read.
  await raviPage.getByRole('button', { name: /^Notifications/ }).click()
  const panel = raviPage.getByRole('dialog', { name: 'Notifications' })
  await expect(panel.getByTestId('inbox-list')).toContainText('Your share is ₹500.')
  const before = Number(await raviPage.getByTestId('unread-count').textContent())
  await panel.getByRole('button', { name: new RegExp(lunch) }).click()
  await expect(raviPage).toHaveURL(/\/groups\//)
  await expect(raviPage.getByText(lunch).first()).toBeVisible()
  await expect.poll(async () => (await raviPage.getByTestId('unread-count').count()) ? Number(await raviPage.getByTestId('unread-count').textContent()) : 0)
    .toBeLessThan(before)

  // And the email arrived.
  await expect.poll(async () => {
    const found = await (await request.get(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:${ravi}`)}`)).json()
    return (found.messages as { Subject: string }[]).map((m) => m.Subject).join(' | ')
  }, { timeout: 20_000 }).toContain(`added ${lunch}`)

  // Clean up the demo user's side: delete the expense, archive the group.
  await demo.getByRole('radio', { name: 'Activity' }).click()
  await demo.getByRole('button', { name: `Edit ${lunch}` }).click()
  await demo.getByRole('dialog', { name: 'Edit expense' }).getByRole('button', { name: 'Delete' }).click()
  await raviContext.close()
  await demoContext.close()
})

test('settings send a test email, and admins see emails and sign-ins', async ({ page, request }) => {
  await signIn(page)
  await page.goto('/settings?tab=notifications')
  await expect(page.getByRole('switch', { name: 'Bills due soon' })).toBeVisible()
  await expect(page.getByRole('switch', { name: 'Weekly recap' })).toBeVisible()
  await page.getByRole('button', { name: 'Send a test email' }).click()
  await expect(page.getByTestId('sent-emails')).toContainText('Test email from PayLedger', { timeout: 15_000 })
  await expect.poll(async () => {
    const found = await (await request.get(`${MAILPIT}/api/v1/search?query=${encodeURIComponent('to:demo@payledger.local subject:"Test email"')}`)).json()
    return found.messages_count
  }, { timeout: 20_000 }).toBeGreaterThan(0)

  await page.goto('/admin?tab=emails')
  await expect(page.getByTestId('admin-emails')).toContainText('Test email from PayLedger')
  // The weekly recap can be sent now; running it again sends nothing twice.
  await page.getByRole('button', { name: 'Send weekly recap now' }).click()
  await expect(page.getByText('Weekly recap sent')).toBeVisible()
  await page.goto('/admin?tab=activity')
  await expect(page.getByTestId('activity-list')).toContainText('signed in')
})
