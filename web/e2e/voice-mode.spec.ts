import { expect, test, type Page } from '@playwright/test'
import { callApi, signIn } from './helpers'

/**
 * A microphone the test speaks into (window.__say), and a silent speech synthesizer: the conversation runs exactly as
 * it would by voice. The assistant's answer is fixed here (the real model's reading is tested in core-service); saving
 * and undoing go through the app's real APIs.
 */
async function scriptedVoice(page: Page) {
  await page.addInitScript(() => {
    type Handler = ((e?: unknown) => void) | null
    class FakeRecognition {
      lang = ''; continuous = false; interimResults = false; maxAlternatives = 1
      onresult: Handler = null; onend: Handler = null; onerror: Handler = null; onspeechstart: Handler = null; onspeechend: Handler = null
      start() { (window as unknown as { __rec: FakeRecognition }).__rec = this }
      stop() { setTimeout(() => this.onend?.(), 20) }
      abort() { setTimeout(() => this.onend?.(), 10) }
    }
    const w = window as unknown as Record<string, unknown>
    for (const name of ['SpeechRecognition', 'webkitSpeechRecognition']) Object.defineProperty(window, name, { value: FakeRecognition, configurable: true, writable: true })
    w.__say = (text: string) => {
      const rec = w.__rec as FakeRecognition | undefined
      if (!rec) return false
      const result = (t: string, isFinal: boolean) => Object.assign([{ transcript: t }], { isFinal })
      rec.onspeechstart?.()
      rec.onresult?.({ resultIndex: 0, results: [result(text, true)] })
      rec.onspeechend?.()
      return true
    }
    Object.defineProperty(window, 'speechSynthesis', {
      configurable: true,
      value: { speak: (u: { onend?: () => void }) => setTimeout(() => u.onend?.(), 30), cancel: () => undefined, getVoices: () => [], addEventListener: () => undefined },
    })
  })
}

async function say(page: Page, text: string) {
  await expect.poll(() => page.evaluate((t) => (window as unknown as { __say: (t: string) => boolean }).__say(t), text), { timeout: 10_000 }).toBe(true)
}

test('voice mode: one sentence becomes several cards, "add them" saves them, "undo that" takes them back', async ({ page }) => {
  await scriptedVoice(page)
  await signIn(page)
  const run = Date.now()
  const tag = String(run).replace(/\d/g, (d) => 'abcdefghij'[Number(d)]!)
  const me = await callApi<{ spaces: { id: string; kind: string }[] }>(page, 'GET', '/api/core/me')
  const space = me.spaces.find((s) => s.kind === 'PERSONAL')!.id
  const account = await callApi<{ id: string }>(page, 'POST', `/api/core/spaces/${space}/accounts`, { kind: 'CASH', name: `E2E Voice wallet ${run}`, openingBalance: 0 })
  const goalName = `E2E Voice goal ${tag}`
  const day = new Date().toISOString().slice(0, 10)

  await page.route('**/api/core/ai/assistant', (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({
      requestId: crypto.randomUUID(), heard: null, intent: null, navigate: null, usage: { limit: null, used: 1 },
      say: 'An entry and a goal ready. Say add them to save.',
      proposals: [
        { id: 'p1', type: 'TRANSACTION', title: `E2E voice petrol ${tag}`, summary: '₹450 · cash · today', amount: 45000, currency: 'INR', confidence: 0.97,
          ready: true, why: [], replaces: null, page: '/transactions',
          body: { kind: 'EXPENSE', amount: 45000, accountId: account.id, toAccountId: null, categoryId: null, occurredOn: day, description: `E2E voice petrol ${tag}`, tags: ['ai', 'voice'] } },
        { id: 'p2', type: 'GOAL', title: `Goal: ${goalName}`, summary: '₹50,000', amount: 5000000, currency: 'INR', confidence: 0.97,
          ready: true, why: [], replaces: null, page: '/goals', body: { name: goalName, target: 5000000, targetDate: null } },
      ],
    }),
  }))

  await page.getByRole('switch', { name: 'Voice mode' }).click()
  const panel = page.getByRole('dialog', { name: 'Voice mode' })
  await expect(panel.getByText('Listening', { exact: true })).toBeVisible()
  await say(page, `450 petrol in cash and a goal of 50,000 called ${goalName}`)
  await expect(panel.getByText(`E2E voice petrol ${tag}`)).toBeVisible()
  await expect(panel.getByText(`Goal: ${goalName}`)).toBeVisible()
  await expect(panel.getByText('An entry and a goal ready. Say add them to save.')).toBeVisible()

  // "Add them": answered on the device, saved through the app's APIs, and the app goes to where it happened.
  await say(page, 'add them')
  await expect(panel.getByText(/^Saved 2\./)).toBeVisible()
  await expect(page).toHaveURL(/\/goals/)
  await expect(page.getByText(goalName).first()).toBeVisible()

  // "Undo that": both taken back.
  await say(page, 'undo that')
  await expect(panel.getByText('Undone.')).toBeVisible()
  const goals = await callApi<{ name: string }[]>(page, 'GET', `/api/core/spaces/${space}/goals`)
  expect(goals.some((g) => g.name === goalName)).toBe(false)

  // "Stop" closes it.
  await say(page, 'stop')
  await expect(panel).toBeHidden()
  await expect(page.getByRole('switch', { name: 'Voice mode' })).toHaveAttribute('aria-checked', 'false')
})

test('voice mode on a phone: the panel sits at the bottom and fits @phone', async ({ page }) => {
  await scriptedVoice(page)
  await signIn(page)
  await page.getByRole('switch', { name: 'Voice mode' }).click()
  const panel = page.getByRole('dialog', { name: 'Voice mode' })
  await expect(panel).toBeVisible()
  const box = (await panel.boundingBox())!
  const vp = page.viewportSize()!
  expect(box.x).toBeGreaterThanOrEqual(0)
  expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
  expect(box.y + box.height).toBeLessThanOrEqual(vp.height + 1)
  await panel.getByRole('button', { name: 'Close voice mode' }).click()
  await expect(panel).toBeHidden()
})
