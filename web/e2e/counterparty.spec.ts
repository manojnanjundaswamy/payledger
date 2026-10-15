import { expect, test } from '@playwright/test'
import { signIn } from './helpers'

test('money from someone: the sender is who it was with, and the list says so', async ({ page }) => {
  await signIn(page)
  const run = Date.now()
  const rupees = 1000 + (run % 900)
  // Letters only: a long number in a name reads as a reference, not a person.
  const sender = `E2E Neha ${String(run).slice(-5).replace(/\d/g, (d) => 'abcdefghij'[Number(d)]!)}`
  const sms = `Your A/C XXXXX12345 Credited INR ${rupees}.00 on 13/10/26 by ${sender.toUpperCase()}. Avl Bal INR 45,210.00 - SBI`

  await page.goto('/capture?method=paste')
  await page.getByLabel('Bank SMS', { exact: true }).fill(sms)
  await page.getByRole('button', { name: 'Read them' }).click()
  const card = page.locator('[data-testid^="review-"]').filter({ hasText: `+₹${rupees.toLocaleString('en-IN')}.00` })
  await expect(card.getByLabel('From whom')).toHaveValue(new RegExp(`^${sender}$`, 'i'))
  await card.getByLabel('Description').fill(`E2E gift ${run}`)
  await card.getByLabel('Account').click()
  await page.getByRole('option').first().click()
  await card.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(card).toBeHidden()

  await page.goto(`/transactions?person=${encodeURIComponent(sender)}`)
  await expect(page.getByText(new RegExp(`from ${sender}`, 'i'))).toBeVisible()
})
