import { expect, test } from '@playwright/test'
import { callApi, signIn } from './helpers'

// Staging has no AI key: quick add gives the simple reading (amount and words) as a draft to check. The AI path
// (sure entries added on their own, masking, the cost log) is proven in core-service's AiIntegrationTest.
test('say or type it: the dashboard bar opens quick add, a draft is checked and added', async ({ page }) => {
  await signIn(page)
  const run = Date.now()
  const me = await callApi<{ spaces: { id: string; kind: string }[] }>(page, 'GET', '/api/core/me')
  const space = me.spaces.find((s) => s.kind === 'PERSONAL')!.id
  const wallet = `E2E Quick wallet ${run}`
  await callApi(page, 'POST', `/api/core/spaces/${space}/accounts`, { kind: 'CASH', name: wallet, openingBalance: 0 })
  const rupees = 100 + (run % 800)
  // Letters, not digits: a number in the words would be read as the amount or left out of the description.
  const tag = String(run).replace(/\d/g, (d) => 'abcdefghij'[Number(d)]!)

  await page.getByRole('button', { name: /Say or type what you spent/ }).click()
  const sheet = page.getByRole('dialog', { name: 'Say it or type it' })
  await expect(sheet).toBeVisible()
  await expect(sheet.getByRole('button', { name: 'Speak an entry' })).toBeVisible()
  await sheet.getByLabel('What you spent or received').fill(`${rupees} E2E quick coffee ${tag}`)
  await sheet.getByRole('button', { name: 'Add', exact: true }).click()

  await expect(sheet.getByText('Check and add')).toBeVisible()
  await expect(sheet.getByLabel('Amount')).toHaveValue(String(rupees))
  await expect(sheet.getByLabel('Description')).toHaveValue(new RegExp(`E2E quick coffee ${tag}`, 'i'))
  await sheet.getByLabel('Account').click()
  await page.getByRole('textbox', { name: 'Search the list' }).fill(wallet)
  await page.getByRole('option', { name: wallet, exact: true }).click()
  await sheet.getByRole('button', { name: 'Add', exact: true }).last().click()
  await expect(sheet.getByText(/^Added:/)).toBeVisible()
  await sheet.getByRole('button', { name: 'Done' }).click()

  await page.goto(`/transactions?q=${encodeURIComponent(`quick coffee ${tag}`)}`)
  await expect(page.getByText(new RegExp(`E2E quick coffee ${tag}`, 'i')).first()).toBeVisible()
})

test('the Add menu offers "Say or type it" first', async ({ page }) => {
  await signIn(page)
  await page.getByRole('button', { name: 'Add', exact: true }).first().click()
  await page.getByRole('menuitem', { name: /Say or type it/ }).click()
  await expect(page.getByRole('dialog', { name: 'Say it or type it' })).toBeVisible()
})

test('on a phone the + opens quick add straight away @phone', async ({ page }) => {
  await signIn(page)
  await page.getByRole('navigation', { name: 'Main' }).getByRole('button', { name: 'Add' }).click()
  const sheet = page.getByRole('dialog', { name: 'Say it or type it' })
  await expect(sheet).toBeVisible()
  // It fits the screen, and the by-hand way is still there.
  const box = await sheet.boundingBox()
  expect(box!.x + box!.width).toBeLessThanOrEqual(page.viewportSize()!.width + 1)
  await expect(sheet.getByRole('button', { name: 'Expense' })).toBeVisible()
})

/**
 * A stand-in for the browser's speech recognition that behaves like the real ones: each phrase is heard (first as
 * "interim", then "final"), then the session ends on the pause, as Chrome on Android does after a second or two.
 * Listening must carry on through the pauses and keep every word.
 */
async function fakeSpeech(page: import('@playwright/test').Page, phrases: string[]) {
  await page.addInitScript((said: string[]) => {
    const queue = [...said]
    class FakeRecognition {
      lang = ''; continuous = false; interimResults = false; maxAlternatives = 1
      onresult: ((e: unknown) => void) | null = null
      onend: (() => void) | null = null
      onerror: ((e: unknown) => void) | null = null
      onspeechstart: (() => void) | null = null
      onspeechend: (() => void) | null = null
      private stopped = false
      start() {
        const phrase = queue.shift()
        const result = (text: string, isFinal: boolean) => Object.assign([{ transcript: text }], { isFinal })
        if (!phrase) { setTimeout(() => this.onend?.(), 200); return }
        const words = phrase.split(' ')
        setTimeout(() => this.onspeechstart?.(), 50)
        setTimeout(() => this.onresult?.({ resultIndex: 0, results: [result(words.slice(0, Math.ceil(words.length / 2)).join(' '), false)] }), 150)
        setTimeout(() => this.onresult?.({ resultIndex: 0, results: [result(phrase, true)] }), 300)
        // The pause: the browser ends the session by itself.
        setTimeout(() => { this.onspeechend?.(); if (!this.stopped) this.onend?.() }, 600)
      }
      stop() { this.stopped = true; setTimeout(() => this.onend?.(), 20) }
      abort() { this.stop() }
    }
    // Newer Chrome has it without the prefix too; the app takes either.
    for (const name of ['SpeechRecognition', 'webkitSpeechRecognition']) {
      Object.defineProperty(window, name, { value: FakeRecognition, configurable: true, writable: true })
    }
  }, phrases)
}

test('speaking keeps listening through pauses and every word lands in the box', async ({ page }) => {
  await fakeSpeech(page, ['450 petrol', 'yesterday on HDFC', 'and 200 auto'])
  await signIn(page)
  await page.getByRole('button', { name: 'Speak an entry' }).first().click()
  const sheet = page.getByRole('dialog', { name: 'Say it or type it' })
  await expect(sheet.getByText(/Listening/)).toBeVisible()
  // Three phrases with pauses between them: still listening, all three kept, in order.
  await expect(sheet.getByText('450 petrol yesterday on HDFC and 200 auto')).toBeVisible({ timeout: 10_000 })
  await expect(sheet.getByText(/Listening/)).toBeVisible()
  await sheet.getByRole('button', { name: 'Stop' }).click()
  await expect(sheet.getByLabel('What you spent or received')).toHaveValue('450 petrol yesterday on HDFC and 200 auto')
})

test('speaking on a phone keeps every word too @phone', async ({ page }) => {
  await fakeSpeech(page, ['250 swiggy', 'on my card'])
  await signIn(page)
  await page.getByRole('navigation', { name: 'Main' }).getByRole('button', { name: 'Add' }).click()
  const sheet = page.getByRole('dialog', { name: 'Say it or type it' })
  await sheet.getByRole('button', { name: 'Speak an entry' }).click()
  await expect(sheet.getByText('250 swiggy on my card')).toBeVisible({ timeout: 10_000 })
  await sheet.getByRole('button', { name: 'Stop' }).click()
  await expect(sheet.getByLabel('What you spent or received')).toHaveValue('250 swiggy on my card')
})
