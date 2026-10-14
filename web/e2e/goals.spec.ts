import { expect, test } from '@playwright/test'
import { signIn } from './helpers'

test('a savings goal says what it needs each month, takes money, and can be archived and deleted', async ({ page }) => {
  await signIn(page)
  const run = Date.now()
  const name = `E2E goal ${run}`
  const by = new Date()
  by.setMonth(by.getMonth() + 9)
  by.setDate(28)
  const byIso = `${by.getFullYear()}-${String(by.getMonth() + 1).padStart(2, '0')}-28`

  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Goals' }).click()
  await page.getByRole('button', { name: 'New goal' }).first().click()
  const dialog = page.getByRole('dialog', { name: 'New goal' })
  await dialog.getByLabel('What for').fill(name)
  await dialog.locator('label').filter({ hasText: '🏖' }).click()
  await dialog.getByLabel('Amount').fill('50,000')
  await dialog.getByLabel('By (optional)').fill(byIso)
  await dialog.getByRole('button', { name: 'Add goal' }).click()
  await expect(dialog).toBeHidden()

  // Ten months including this one: ₹5,000 a month.
  const card = page.getByTestId(`goal-${name}`)
  await expect(card.getByTestId('goal-advice')).toContainText('Put aside ₹5,000 a month')
  await expect(card.getByTestId('goal-status')).toHaveText('On track')

  // Add money: the monthly amount drops.
  await card.getByRole('button', { name: 'Add money' }).click()
  const add = page.getByRole('dialog', { name: `Add money to ${name}` })
  await expect(add.getByLabel('Amount')).toHaveValue('5000')
  await add.getByLabel('Amount').fill('20,000')
  await add.getByLabel('Note (optional)').fill('Bonus')
  await add.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(card.getByTestId('goal-saved')).toHaveText('₹20,000')
  await expect(card.getByTestId('goal-advice')).toContainText('Put aside ₹3,000 a month')

  // Archive, find it among archived, delete it.
  await card.getByRole('button', { name: `Archive ${name}` }).click()
  await expect(card).toBeHidden()
  await page.getByRole('switch', { name: 'Show archived goals' }).click()
  await expect(card).toContainText('Archived')
  await card.getByRole('button', { name: `Delete ${name}` }).click()
  await expect(card).toBeHidden()
})
