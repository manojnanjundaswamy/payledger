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

test('a shared expense shows who lent what, and settling up evens it out', async ({ page }) => {
  await signIn(page)
  const run = Date.now()
  await page.goto('/groups')
  await page.getByRole('button', { name: 'New group' }).first().click()
  const dialog = page.getByRole('dialog', { name: 'New group' })
  await dialog.getByLabel('Name').fill(`E2E split ${run}`)
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
  await expect(page.getByRole('button', { name: 'Edit Dinner' })).toContainText('you lent ₹450.00')

  // Balances come from the ledger a moment later.
  await expect(page.getByTestId('my-balance')).toHaveText("You're owed ₹450.00", { timeout: 15_000 })
  const payment = page.getByTestId('payment-Amit-You')
  await expect(payment).toContainText('₹450.00')
  await payment.getByRole('button', { name: 'Record payment' }).click()
  await page.getByRole('dialog', { name: 'Record a payment' }).getByRole('button', { name: 'Record payment' }).click()
  await expect(page.getByTestId('my-balance')).toHaveText('All settled', { timeout: 15_000 })
  await expect(page.getByText('Amit paid You')).toBeVisible()

  await page.getByRole('button', { name: 'Archive group' }).click()
})
