import { expect, test } from '@playwright/test'
import { callApi, signIn } from './helpers'

test('adding from a filtered list starts with the filter, and can start blank', async ({ page }) => {
  await signIn(page)
  const me = await callApi<{ spaces: { id: string }[] }>(page, 'GET', '/api/core/me')
  const categories = await callApi<{ id: string; name: string; kind: string }[]>(page, 'GET', `/api/core/spaces/${me.spaces[0]!.id}/categories`)
  const education = categories.find((c) => c.kind === 'EXPENSE' && c.name === 'Education')!

  await page.goto(`/transactions?category=${education.id}`)
  await page.getByRole('main').getByRole('button', { name: 'Add', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Add expense' })
  await expect(dialog.getByTestId('carried')).toContainText('Education')
  await expect(dialog.getByLabel('Category')).toContainText('Education')
  await dialog.getByRole('button', { name: 'Start blank' }).click()
  await expect(dialog.getByTestId('carried')).toBeHidden()
  await expect(dialog.getByLabel('Category')).not.toContainText('Education')
})
