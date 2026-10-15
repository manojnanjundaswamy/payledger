import { expect, test } from '@playwright/test'
import { callApi, signIn } from './helpers'

test('a bank statement goes into To review, adds up, and Add all brings it in', async ({ page }) => {
  await signIn(page)
  const run = Date.now()
  // Letters only in the shop's name: long numbers in a narration read as references.
  const shop = `E2E STMT SHOP ${String(run).slice(-6).replace(/\d/g, (d) => 'abcdefghij'[Number(d)]!).toUpperCase()}`
  const me = await callApi<{ spaces: { id: string }[] }>(page, 'GET', '/api/core/me')
  const bank = `E2E stmt bank ${run}`
  await callApi(page, 'POST', `/api/core/spaces/${me.spaces[0]!.id}/accounts`, { kind: 'BANK', name: bank, openingBalance: 0 })
  const csv = [
    'Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance',
    `01/10/26,NEFT CR-E2E PAYROLL ${run},N1,01/10/26,,"10,000.00","15,000.00"`,
    `02/10/26,UPI-${shop}-shop@ybl-ICIC0DC0099-${run}-PAYMENT,R2,02/10/26,250.00,,"14,750.00"`,
  ].join('\n')

  await page.goto('/capture?method=statement')
  await page.getByLabel('Statement file').setInputFiles({ name: 'statement.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) })
  await page.getByLabel('Statement account').click()
  await page.getByRole('textbox', { name: 'Search the list' }).fill(bank)
  await page.getByRole('option', { name: bank, exact: true }).click()
  await page.getByRole('button', { name: 'Read the statement' }).click()
  const result = page.getByTestId('statement-result')
  await expect(result).toContainText('2 new')
  await expect(result).toContainText('Balances add up')

  await page.getByRole('button', { name: /Add all \d+ that look right/ }).click()
  await expect(page.getByText(/\d+ added/).first()).toBeVisible()
  await page.goto(`/transactions?q=${encodeURIComponent(shop)}`)
  await expect(page.getByText(new RegExp(shop, 'i')).first()).toBeVisible()
})
