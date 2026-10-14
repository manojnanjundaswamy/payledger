import { expect, test } from '@playwright/test'
import { signIn } from './helpers'

test.beforeEach(async ({ page }) => {
  await signIn(page)
})

test('the edge panel opens a calculator window that moves, minimises, restores and closes', async ({ page }) => {
  await page.getByRole('button', { name: 'Open utilities' }).click()
  await page.getByRole('dialog', { name: 'Utilities' }).getByRole('button', { name: 'Calculator' }).click()
  const win = page.getByTestId('window-calculator')
  await expect(win).toBeVisible()

  // It calculates as you type, the Indian way.
  await win.getByLabel('Expression').fill('1.5L + 18%')
  await expect(win.getByTestId('calc-result')).toHaveText('1,77,000')
  await win.getByLabel('Expression').press('Enter')
  await win.getByRole('button', { name: 'minus' }).click()
  await win.getByRole('button', { name: '7', exact: true }).click()
  await win.getByRole('button', { name: '=', exact: true }).click()
  await expect(win.getByTestId('calc-result')).toHaveText('1,76,993')
  await expect(win.getByRole('region', { name: 'History' })).toContainText('1.5L + 18%')

  // Dragged by its title bar.
  const before = (await win.boundingBox())!
  const bar = win.locator('header')
  const barBox = (await bar.boundingBox())!
  await page.mouse.move(barBox.x + 40, barBox.y + 15)
  await page.mouse.down()
  await page.mouse.move(barBox.x - 160, barBox.y + 95, { steps: 6 })
  await page.mouse.up()
  const after = (await win.boundingBox())!
  expect(Math.round(after.x)).toBeLessThan(Math.round(before.x) - 100)
  expect(Math.round(after.y)).toBeGreaterThan(Math.round(before.y) + 50)

  // Minimised to the tray, restored where it was, then closed.
  await win.getByRole('button', { name: 'Minimise' }).click()
  await expect(win).toBeHidden()
  await page.getByRole('button', { name: 'Restore Calculator' }).click()
  await expect(win).toBeVisible()
  await expect(win.getByTestId('calc-result')).toHaveText('1,76,993')
  await win.getByRole('button', { name: 'Maximise' }).click()
  await win.getByRole('button', { name: 'Restore' }).click()
  await win.getByRole('button', { name: 'Close' }).click()
  await expect(win).toBeHidden()
})

test('the utilities page has every tool, and they work', async ({ page }) => {
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Utilities' }).click()
  await expect(page.getByRole('navigation', { name: 'Utilities' })).toBeVisible()

  await page.getByRole('navigation', { name: 'Utilities' }).getByRole('button', { name: 'Loan EMI' }).click()
  await page.getByLabel('Loan amount').fill('50L')
  await page.getByLabel('Interest', { exact: true }).fill('8.5')
  await page.getByLabel('Tenure').fill('20')
  await expect(page.getByTestId('emi')).toHaveText('₹43,391')

  await page.getByRole('navigation', { name: 'Utilities' }).getByRole('button', { name: 'Income tax' }).click()
  await page.getByLabel('Yearly income').fill('12,75,000')
  await expect(page.getByTestId('tax-new')).toContainText('₹0')
  await expect(page.getByTestId('tax-better')).toContainText('new regime')

  await page.getByRole('navigation', { name: 'Utilities' }).getByRole('button', { name: 'GST' }).click()
  await expect(page.getByTestId('gst-result')).toHaveText('₹1,180.00')

  await page.getByRole('navigation', { name: 'Utilities' }).getByRole('button', { name: 'Amount in words' }).click()
  await page.getByLabel('Amount', { exact: true }).fill('2,35,00,000')
  await expect(page.getByTestId('words')).toHaveText('Rupees two crore thirty-five lakh only')

  // Currency: live reference rates (or, without the internet, a typed rate).
  await page.getByRole('navigation', { name: 'Utilities' }).getByRole('button', { name: 'Currency' }).click()
  await page.getByLabel(/Your rate/).fill('85')
  await page.getByLabel('Amount in ₹').fill('8,500')
  await expect(page.getByTestId('fx-result')).toContainText('100')

  // Any tool pops out into a window.
  await page.getByRole('button', { name: 'Pop out' }).click()
  await expect(page.getByTestId('window-currency')).toBeVisible()
})

test('the edge panel can be turned off and on', async ({ page }) => {
  await page.goto('/utilities')
  await page.getByRole('switch', { name: 'Edge panel' }).click()
  await expect(page.getByRole('button', { name: 'Open utilities' })).toBeHidden()
  await page.getByRole('switch', { name: 'Edge panel' }).click()
  await expect(page.getByRole('button', { name: 'Open utilities' })).toBeVisible()
})

// Nothing is saved: the dialogs open filled in from the answer and are cancelled.
test("a calculator's answer can be added as an expense, or a loan's EMI to the plan", async ({ page }) => {
  await page.goto('/utilities?tool=calculator')
  await page.getByLabel('Expression').fill('250 * 2')
  await expect(page.getByTestId('calc-result')).toHaveText('500')
  await page.getByRole('button', { name: 'Add as an expense' }).click()
  const add = page.getByRole('dialog', { name: 'Add expense' })
  await expect(add.getByLabel('Amount')).toHaveValue('500')
  await add.getByRole('button', { name: 'Cancel' }).click()

  await page.goto('/utilities?tool=loan')
  const emi = (await page.getByTestId('emi').textContent())!.replace(/[₹,]/g, '')
  await page.getByRole('button', { name: 'Add to my plan' }).click()
  const plan = page.getByRole('dialog', { name: 'Add to your plan' })
  await expect(plan.getByLabel('Name')).toHaveValue('Loan EMI')
  await expect(plan.getByLabel('Amount')).toHaveValue(new RegExp(`^${emi.slice(0, 4)}`))
  await expect(plan.getByLabel('Category', { exact: true })).toHaveText(/EMIs & loans/)
  await plan.getByRole('button', { name: 'Cancel' }).click()

  await page.goto('/utilities?tool=split')
  await page.getByRole('button', { name: 'Add as an expense' }).click()
  await expect(page.getByRole('dialog', { name: 'Add expense' }).getByLabel('Description')).toHaveValue('My share of the bill')
})
