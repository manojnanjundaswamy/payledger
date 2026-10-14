import { test } from '@playwright/test'
import { callApi, signIn } from './helpers'

/**
 * Not a test: scripts/seed-sample-data.sh runs it (SEED=1) to give the demo account three months of realistic,
 * balanced data: a salary, rent, bills, groceries, eating out, travel, a card paid off each month, a monthly SIP,
 * budgets, a plan, two goals, rules and a flat-share group. Everything goes through the API (so the ledger, budgets
 * and bill matching are all real) and is tagged "sample". It does nothing if "HDFC Salary" already exists.
 */
test('seed three months of sample data into the demo account', async ({ page }) => {
  test.setTimeout(900_000)
  await signIn(page)
  const me = await callApi<{ spaces: { id: string }[] }>(page, 'GET', '/api/core/me')
  const base = `/api/core/spaces/${me.spaces[0]!.id}`
  const accounts = await callApi<{ id: string; name: string }[]>(page, 'GET', `${base}/accounts?includeArchived=true`)
  if (accounts.some((a) => a.name === 'HDFC Salary')) {
    console.log('seed: already there, nothing to do')
    return
  }

  // A seeded random, so every run makes the same months.
  let state = 20261014
  const rnd = () => ((state = (state * 1103515245 + 12345) % 2147483648) / 2147483648)
  const between = (lo: number, hi: number) => Math.round((lo + rnd() * (hi - lo)) / 10) * 10
  const pick = <T,>(xs: T[]) => xs[Math.floor(rnd() * xs.length)]!

  let categories = await callApi<{ id: string; name: string; kind: string }[]>(page, 'GET', `${base}/categories`)
  if (!categories.some((c) => c.name === 'Transport')) {
    await callApi(page, 'POST', `${base}/categories`, { kind: 'EXPENSE', name: 'Transport', icon: 'car', color: 'series-4' })
    categories = await callApi(page, 'GET', `${base}/categories`)
  }
  const cat = (name: string) => categories.find((c) => c.name === name)!.id

  const account = async (kind: string, name: string, openingBalance: number) =>
    (await callApi<{ id: string }>(page, 'POST', `${base}/accounts`, { kind, name, openingBalance, openingDate: '2026-07-31' })).id
  const hdfc = await account('BANK', 'HDFC Salary', 8_500_000)
  const sbi = await account('BANK', 'SBI Savings', 25_000_000)
  const card = await account('CARD', 'ICICI Amazon Pay card', 0)
  const cash = await account('CASH', 'Cash', 500_000)
  const paytm = await account('WALLET', 'Paytm wallet', 150_000)

  // Goals and the plan first, so the payments entered below are matched to their bills.
  const goa = (await callApi<{ id: string }>(page, 'POST', `${base}/goals`, { name: 'Goa trip', emoji: '🏖️', target: 6_000_000, targetDate: '2027-02-15' })).id
  await callApi(page, 'POST', `${base}/goals`, { name: 'Emergency fund', emoji: '🛟', target: 30_000_000, targetDate: '2027-12-31', accountId: sbi })
  const bill = (name: string, amount: number, categoryId: string, firstDue: string, everyMonths = 1, accountId = hdfc) =>
    callApi(page, 'POST', `${base}/commitments`, { kind: 'BILL', name, amount, accountId, categoryId, toAccountId: null, everyMonths, firstDue })
  await bill('Rent', 2_800_000, cat('Rent'), '2026-08-03')
  await bill('Car loan EMI', 1_250_000, cat('EMIs & loans'), '2026-08-05')
  await bill('Netflix', 64_900, cat('Subscriptions'), '2026-08-05', 1, card)
  await bill('Health insurance', 1_800_000, cat('Insurance'), '2026-12-15', 12)
  await callApi(page, 'POST', `${base}/commitments`, { kind: 'SAVING', name: 'SIP into SBI', amount: 1_000_000, accountId: hdfc, toAccountId: sbi, categoryId: null, everyMonths: 1, firstDue: '2026-08-07' })

  const txn = (kind: string, amount: number, accountId: string, description: string, occurredOn: string, extra: Record<string, unknown> = {}) =>
    callApi(page, 'POST', `${base}/transactions`, { kind, amount, accountId, description, occurredOn, tags: ['sample'], ...extra })
  const spend = (amount: number, accountId: string, category: string, description: string, day: string) =>
    txn('EXPENSE', amount, accountId, description, day, { categoryId: cat(category) })
  const move = (amount: number, from: string, to: string, description: string, day: string, goalId?: string) =>
    txn('TRANSFER', amount, from, description, day, { toAccountId: to, goalId })

  const today = '2026-10-14'
  let cardOwed = 0
  for (const month of ['2026-08', '2026-09', '2026-10']) {
    const d = (day: number) => `${month}-${String(day).padStart(2, '0')}`
    const until = (day: number) => d(day) <= today
    const onCard = async (amount: number, category: string, description: string, day: number) => {
      if (!until(day)) return
      cardOwed += amount
      await spend(amount, card, category, description, d(day))
    }
    // Pay last month's card bill in full on the 18th.
    if (until(18) && cardOwed > 0 && month !== '2026-08') {
      await move(cardOwed, hdfc, card, 'ICICI card bill', d(18))
      cardOwed = 0
    }
    if (until(1)) await txn('INCOME', 12_000_000, hdfc, 'Salary', d(1), { categoryId: cat('Salary') })
    if (until(3)) await spend(2_800_000, hdfc, 'Rent', 'Rent', d(3))
    if (until(5)) await spend(1_250_000, hdfc, 'EMIs & loans', 'Car loan EMI', d(5))
    await onCard(64_900, 'Subscriptions', 'Netflix', 5)
    await onCard(11_900, 'Subscriptions', 'Spotify', 12)
    if (until(7)) await move(1_000_000, hdfc, sbi, 'SIP into SBI', d(7))
    if (until(9)) await spend(between(170_000, 240_000), hdfc, 'Utilities', 'BESCOM electricity', d(9))
    if (until(10)) await spend(99_900, hdfc, 'Utilities', 'ACT Fibernet', d(10))
    if (until(11)) await spend(59_900, paytm, 'Utilities', 'Jio recharge', d(11))
    if (until(4)) await move(300_000, hdfc, cash, 'ATM withdrawal', d(4))
    if (until(15)) await move(500_000, hdfc, sbi, 'Goa trip savings', d(15), goa)
    if (until(25)) await txn('INCOME', between(15_000, 60_000), hdfc, 'Savings interest', d(25), { categoryId: cat('Interest & dividends') })
    for (const day of [2, 9, 16, 23, 30]) {
      if (until(day)) await onCard(between(180_000, 320_000), 'Groceries', pick(['BigBasket', 'Blinkit', 'DMart', 'Zepto']), day)
    }
    for (const day of [3, 6, 8, 12, 14, 19, 22, 26, 28]) {
      if (until(day)) await spend(between(25_000, 90_000), pick([paytm, card]), 'Eating out', pick(['Swiggy', 'Zomato', 'Chai Point', 'Third Wave Coffee']), d(day))
    }
    for (const day of [2, 7, 13, 18, 24, 29]) {
      if (until(day)) await spend(between(18_000, 45_000), paytm, 'Transport', pick(['Uber', 'Ola', 'Rapido']), d(day))
    }
    for (const day of [6, 21]) if (until(day)) await onCard(between(200_000, 300_000), 'Transport', 'Indian Oil petrol', day)
    for (const day of [5, 11, 17, 27]) if (until(day)) await spend(between(5_000, 20_000), cash, 'Eating out', pick(['Tea stall', 'Darshini breakfast', 'Juice']), d(day))
    if (until(13)) await onCard(between(150_000, 400_000), 'Shopping', pick(['Amazon', 'Myntra', 'Decathlon']), 13)
    if (until(20)) await spend(between(40_000, 90_000), hdfc, 'Health', 'Apollo Pharmacy', d(20))
    if (until(26)) await spend(between(100_000, 250_000), hdfc, 'Family & gifts', pick(['Gift for Amma', 'Birthday gift', 'Diwali sweets']), d(26))
    if (until(10)) await spend(250_000, hdfc, 'Household help', 'Maid salary', d(10))
  }

  // Budgets from August, a little above what these months spend, so some are close and one is over.
  for (const [name, amount] of [['Groceries', 1_200_000], ['Eating out', 500_000], ['Transport', 700_000], ['Shopping', 300_000],
    ['Utilities', 400_000], ['Subscriptions', 100_000], ['Health', 150_000]] as const) {
    await callApi(page, 'PUT', `${base}/budgets/${cat(name)}`, { amount, rollover: false, from: '2026-08' })
  }
  for (const [match, name] of [['swiggy', 'Eating out'], ['zomato', 'Eating out'], ['uber', 'Transport'], ['bigbasket', 'Groceries']] as const) {
    await callApi(page, 'POST', `${base}/category-rules`, { match, categoryId: cat(name) })
  }

  // A flat share with two friends who haven't joined yet (placeholders), three months of it, and a payment.
  const group = await callApi<{ id: string }>(page, 'POST', '/api/core/groups', { name: 'Flat 302', emoji: '🏠' })
  await callApi(page, 'POST', `/api/core/groups/${group.id}/members`, { name: 'Rahul' })
  const members = await callApi<{ members: { id: string; name: string; you: boolean }[] }>(page, 'POST', `/api/core/groups/${group.id}/members`, { name: 'Neha' })
  const you = members.members.find((m) => m.you)!.id
  const rahul = members.members.find((m) => m.name === 'Rahul')!.id
  const neha = members.members.find((m) => m.name === 'Neha')!.id
  const shared = (description: string, amount: number, payer: string, occurredOn: string, category: string) =>
    callApi(page, 'POST', `/api/core/groups/${group.id}/expenses`, {
      description, amount, occurredOn, category, paidBy: [{ memberId: payer, value: amount }], split: 'EQUAL',
      shares: [{ memberId: you }, { memberId: rahul }, { memberId: neha }],
    })
  for (const month of ['2026-08', '2026-09', '2026-10']) {
    await shared('Groceries for the flat', between(240_000, 360_000), pick([you, rahul, neha]), `${month}-02`, 'Groceries')
    await shared('Cook', 900_000, you, `${month}-05`, 'Household help')
    if (`${month}-12` <= today) await shared('Wifi and gas', 180_000, rahul, `${month}-12`, 'Utilities')
  }
  await callApi(page, 'POST', `/api/core/groups/${group.id}/settlements`, { fromMemberId: neha, toMemberId: you, amount: 300_000, occurredOn: '2026-09-15', note: 'UPI' })
  console.log('seed: done')
})
