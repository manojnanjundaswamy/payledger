import { expect, test } from '@playwright/test'
import { callApi, signUp } from './helpers'

const SECRET = process.env.INBOUND_SECRET ?? 'staging-inbound-secret'

test('once emails come through, setup steps aside for a status card that shows what came of each', async ({ page, request }) => {
  await signUp(page, request, `e2e-forward-${Date.now()}@example.com`)
  const { address } = await callApi<{ address: string }>(page, 'GET', '/api/core/capture')
  await page.goto('/capture?method=email')
  await expect(page.getByText('Add it as a forwarding address in Gmail')).toBeVisible()

  const send = (from: string, subject: string, body: string) => request.post('/inbound/email', {
    headers: { 'Content-Type': 'message/rfc822', 'X-Inbound-Secret': SECRET, 'X-Envelope-To': address },
    data: `From: ${from}\r\nSubject: ${subject}\r\n\r\n${body}\r\n`,
  })
  expect((await (await send('offers@hdfcbank.net', 'E2E pre-approved loan offer', 'Get a pre-approved loan at 10.5%. Apply now.')).json()).result).toBe('not-a-transaction')
  expect((await (await send('alerts@hdfcbank.net', 'E2E You have done a UPI txn', 'Rs.210.00 has been debited from account **1234 to VPA e2eshop@okicici on 15-10-26. UPI ref 529000210.')).json()).result).toBe('added')

  await page.reload()
  const status = page.getByTestId('forwarding-status')
  await expect(status).toContainText('Email forwarding is on')
  await expect(status).toContainText('2 this week')
  const recent = page.getByTestId('recent-emails')
  await expect(recent.getByRole('listitem').first()).toContainText('Added to review')
  await expect(recent).toContainText('Skipped: not a transaction')
  await expect(page.getByText('Add it as a forwarding address in Gmail')).toBeHidden()
  await status.getByRole('button', { name: 'Show setup' }).click()
  await expect(page.getByText('Add it as a forwarding address in Gmail')).toBeVisible()
  await page.getByRole('button', { name: 'Hide setup' }).click()
  await expect(page.getByTestId('forwarding-status')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})
