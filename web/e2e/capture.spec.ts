import { expect, test } from '@playwright/test'
import { callApi, signIn } from './helpers'

test('pasted bank SMS wait in To review, guessed, and become a transaction when added', async ({ page }) => {
  await signIn(page)
  const run = Date.now()
  const me = await callApi<{ spaces: { id: string }[] }>(page, 'GET', '/api/core/me')
  const bank = `E2E HDFC ${run}`
  await callApi(page, 'POST', `/api/core/spaces/${me.spaces[0]!.id}/accounts`, { kind: 'BANK', name: bank, openingBalance: 0 })
  // A unique amount, so this run's SMS is new (the same text twice is kept once).
  const rupees = 100 + (run % 800)
  const sms = `Rs.${rupees}.00 debited from a/c **${String(run).slice(-4)} on 14-10-26 to VPA e2ecafe@ybl (UPI Ref No ${run}) - HDFC Bank`

  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: /Auto-capture/ }).click()
  await page.getByRole('tab', { name: /Paste SMS/ }).click()
  await page.getByLabel('Bank SMS', { exact: true }).fill(`${sms}\n\n123456 is your OTP for Rs 999 at Amazon. Do not share it.`)
  await page.getByRole('button', { name: 'Read them' }).click()
  await expect(page.getByText('1 entry to review')).toBeVisible()

  const card = page.getByTestId('review-E2ecafe').filter({ hasText: `₹${rupees}.00` })
  await expect(card).toContainText(`−₹${rupees}.00`)
  await expect(card).toContainText('HDFC Bank')
  await card.getByLabel('Description').fill(`E2E capture ${run}`)
  await card.getByLabel('Account').click()
  await page.getByRole('textbox', { name: 'Search the list' }).fill(bank)
  await page.getByRole('option', { name: bank, exact: true }).click()
  await card.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(card).toBeHidden()

  await page.goto(`/transactions?q=${encodeURIComponent(`E2E capture ${run}`)}`)
  await expect(page.getByText(`E2E capture ${run}`)).toBeVisible()
})

test('the email guide shows your own address and the Gmail filter to copy', async ({ page }) => {
  await signIn(page)
  await page.goto('/capture')
  await expect(page.getByRole('button', { name: 'Copy Address' })).toBeVisible()
  await expect(page.getByText(/@in\./).first()).toBeVisible()
  await expect(page.getByRole('button', { name: 'Copy Filter' })).toBeVisible()
})
