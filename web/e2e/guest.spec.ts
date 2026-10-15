import { expect, test, type APIRequestContext } from '@playwright/test'
import { MAILPIT, callApi, signUp } from './helpers'

async function codeFor(request: APIRequestContext, email: string) {
  let code = ''
  await expect.poll(async () => {
    const found = await (await request.get(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:${email}`)}`)).json()
    code = found.messages_count ? (found.messages[0].Subject as string).slice(0, 6) : ''
    return code
  }, { timeout: 15_000 }).toMatch(/^\d{6}$/)
  return code
}

test('a guest without an account joins by link, adds an expense and the group sees it', async ({ page, request, browser }) => {
  await signUp(page, request, `e2e-guest-owner-${Date.now()}@example.com`)
  const group = await callApi<{ id: string }>(page, 'POST', '/api/core/groups', { name: `E2E Guest trip ${Date.now()}` })
  await callApi(page, 'POST', `/api/core/groups/${group.id}/members`, { name: 'Ravi' })
  const { token } = await callApi<{ token: string }>(page, 'GET', `/api/core/groups/${group.id}/link`)

  // Someone with no account, in their own browser.
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const guest = await context.newPage()
  await guest.goto(`/join/${token}`)
  await expect(guest).toHaveURL(new RegExp(`/guest/join/${token}`))
  await expect(guest.getByTestId('invite-name')).toContainText('E2E Guest trip')
  await guest.getByRole('button', { name: 'Continue as a guest' }).click()
  await expect(guest.getByRole('radio', { name: 'Ravi' })).toHaveAttribute('aria-checked', 'true')
  const email = `e2e-ravi-${Date.now()}@example.com`
  await guest.getByLabel('Your email').fill(email)
  await guest.getByRole('button', { name: 'Email me a code' }).click()
  await guest.getByLabel('Code').fill(await codeFor(request, email))
  await guest.getByRole('button', { name: 'Join the group' }).click()

  await expect(guest.getByTestId('guest-group-name')).toContainText('E2E Guest trip')
  await guest.getByRole('button', { name: 'Add an expense' }).click()
  const dialog = guest.getByRole('dialog', { name: 'Add an expense' })
  await dialog.getByLabel('What for').fill('Fuel')
  await dialog.getByLabel('Amount').fill('1000')
  await dialog.getByRole('button', { name: 'Add expense' }).click()
  await expect(guest.getByTestId('guest-expenses')).toContainText('Fuel')
  await expect(guest.getByTestId('guest-balances')).toContainText('You\'re owed', { timeout: 15_000 })

  // The owner sees it, and asks about it in a comment.
  await page.goto(`/groups/${group.id}`)
  await page.getByRole('radio', { name: 'Activity' }).click()
  await page.getByRole('button', { name: 'Edit Fuel' }).click()
  const thread = page.getByTestId('expense-comments')
  await thread.getByLabel('Write a comment').fill('Was this the drive to Gokarna?')
  await thread.getByRole('button', { name: 'Send comment' }).click()
  await expect(thread).toContainText('Was this the drive to Gokarna?')
  await page.keyboard.press('Escape')
  await expect(page.getByLabel('1 comment', { exact: true })).toBeVisible()

  // The guest sees it and answers.
  await guest.reload()
  await guest.getByTestId('guest-expenses').getByRole('button', { name: /Fuel/ }).click()
  const guestThread = guest.getByTestId('expense-comments')
  await expect(guestThread).toContainText('Was this the drive to Gokarna?')
  await guestThread.getByLabel('Write a comment').fill('Yes, both ways')
  await guestThread.getByRole('button', { name: 'Send comment' }).click()
  await expect(guestThread).toContainText('Yes, both ways')

  // A reload keeps the guest in (their cookie); signing out ends it.
  await guest.reload()
  await expect(guest.getByTestId('guest-group-name')).toBeVisible()
  await guest.getByRole('button', { name: 'Sign out' }).click()
  await guest.goto(`/guest/groups/${group.id}`)
  await expect(guest.getByText('Your guest session has ended')).toBeVisible()
  await context.close()
})
