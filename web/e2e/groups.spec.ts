import { expect, test } from '@playwright/test'
import { DEMO, signIn } from './helpers'

test('a group gets people by name, email and an invite link that works after signing in', async ({ page, browser }) => {
  await signIn(page)
  const run = Date.now()
  const name = `E2E group ${run}`

  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Groups' }).click()
  await page.getByRole('button', { name: 'New group' }).first().click()
  const dialog = page.getByRole('dialog', { name: 'New group' })
  await dialog.getByLabel('Name').fill(name)
  await dialog.getByRole('button', { name: 'Create group' }).click()
  await expect(page.getByTestId('group-name')).toHaveText(name)
  await expect(page.locator('[data-testid^="member-"]').filter({ hasText: '(you)' })).toContainText('Owner')

  const addSomeone = page.getByRole('region', { name: 'Add someone' })
  await addSomeone.getByLabel('Name').fill('Amit')
  await addSomeone.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(page.getByTestId('member-Amit')).toContainText('Not joined yet')
  await addSomeone.getByLabel('Name').fill('Neha')
  await addSomeone.getByLabel('Email (optional)').fill(`neha-${run}@example.com`)
  await addSomeone.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(page.getByTestId('member-Neha')).toContainText(`Invited: neha-${run}@example.com`)
  await addSomeone.getByLabel('Name').fill('Neha twice')
  await addSomeone.getByLabel('Email (optional)').fill(`neha-${run}@example.com`)
  await addSomeone.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(addSomeone.getByRole('alert')).toHaveText('Someone with that email is already in the group.')

  await page.getByRole('button', { name: 'Show invite link' }).click()
  const link = page.getByRole('textbox', { name: 'Invite link' })
  await expect(link).toHaveValue(/\/join\/[\w-]{32}$/)
  const url = await link.inputValue()

  // Opened signed out: after signing in, the link page is where you land.
  const fresh = await browser.newPage()
  await fresh.goto(url)
  await fresh.getByRole('link', { name: 'Sign in' }).first().click()
  await fresh.locator('#username').fill(DEMO.email)
  await fresh.locator('#password').fill(DEMO.password)
  await fresh.locator('#kc-login').click()
  await expect(fresh.getByTestId('invite-name')).toHaveText(name)
  await fresh.getByRole('button', { name: 'Open the group' }).click()
  await expect(fresh.getByTestId('group-name')).toHaveText(name)
  await fresh.close()

  // Clean up: archive it (groups aren't deleted; their history stays).
  await page.getByRole('button', { name: 'Archive group' }).click()
  await expect(page.getByText('archived', { exact: false }).first()).toBeVisible()
})

test('a shared expense shows who lent what, in charts, on the calendar and in the list, and settling up evens it out', async ({ page }) => {
  await signIn(page)
  const run = Date.now()
  const groupName = `E2E split ${run}`
  await page.goto('/groups')
  await page.getByRole('button', { name: 'New group' }).first().click()
  const dialog = page.getByRole('dialog', { name: 'New group' })
  await dialog.getByLabel('Name').fill(groupName)
  await dialog.getByRole('button', { name: 'Create group' }).click()
  const addSomeone = page.getByRole('region', { name: 'Add someone' })
  await addSomeone.getByLabel('Name').fill('Amit')
  await addSomeone.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(page.getByTestId('member-Amit')).toBeVisible()

  await page.getByRole('button', { name: 'Add expense' }).click()
  const add = page.getByRole('dialog', { name: 'Add a shared expense' })
  await add.getByLabel('What for').fill('Dinner')
  await add.getByLabel('Amount').fill('900')
  // Equal split between you and Amit, previewed before saving.
  await expect(add.getByTestId('share-Amit')).toHaveText('₹450.00')
  await add.getByRole('button', { name: 'Add expense' }).click()
  await expect(add).toBeHidden()
  await page.getByRole('radio', { name: 'Activity' }).click()
  await expect(page.getByRole('button', { name: 'Edit Dinner' })).toContainText('you lent ₹450.00')
  // Search the group's activity.
  await page.getByLabel('Search this group').fill('lunch')
  await expect(page.getByText('Nothing matches.')).toBeVisible()
  await page.getByLabel('Search this group').fill('dinn')
  await expect(page.getByTestId('group-found')).toContainText('1 of 1')

  // The overview: totals and charts; a chart opens what's behind it.
  await page.getByRole('radio', { name: 'Overview' }).click()
  await expect(page.getByTestId('group-spent')).toHaveText('₹900')
  await expect(page.getByTestId('group-your-share')).toHaveText('₹450')
  await expect(page.getByRole('figure', { name: 'Who paid' })).toContainText('You')
  await page.getByRole('button', { name: 'Open Dinner' }).click()
  await expect(page.getByRole('dialog', { name: 'Edit expense' })).toBeVisible()
  await page.keyboard.press('Escape')

  // The calendar has it today, and a day adds an expense on that date.
  await page.getByRole('radio', { name: 'Calendar' }).click()
  const now = new Date()
  const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
  await expect(page.getByTestId(`day-${today}`)).toContainText('Dinner')
  await page.getByTestId(`day-${today}`).click({ position: { x: 4, y: 4 } })
  await page.getByRole('menu').getByRole('menuitem', { name: 'Add an expense on this day' }).click()
  await expect(page.getByRole('dialog', { name: 'Add a shared expense' })).toBeVisible()
  await page.getByRole('dialog', { name: 'Add a shared expense' }).getByRole('button', { name: 'Cancel' }).click()
  await page.getByRole('radio', { name: 'Overview' }).click()

  // Balances come from the ledger a moment later.
  await expect(page.getByTestId('my-balance')).toHaveText("You're owed ₹450.00", { timeout: 15_000 })
  // The groups list shows it too.
  await page.goto('/groups')
  await expect(page.getByTestId(`group-card-${groupName}`)).toContainText("You're owed ₹450")
  await page.getByTestId(`group-card-${groupName}`).click()
  const payment = page.getByTestId('payment-Amit-You')
  await expect(payment).toContainText('₹450.00')
  await payment.getByRole('button', { name: 'Record payment' }).click()
  await page.getByRole('dialog', { name: 'Record a payment' }).getByRole('button', { name: 'Record payment' }).click()
  await expect(page.getByTestId('my-balance')).toHaveText('All settled', { timeout: 15_000 })
  await page.getByRole('radio', { name: 'Activity' }).click()
  await expect(page.getByText('Amit paid You')).toBeVisible()

  await page.getByRole('radio', { name: 'People' }).click()
  await page.getByRole('button', { name: 'Archive group' }).click()
})
