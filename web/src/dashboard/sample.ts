/**
 * Realistic sample numbers (Indian context, paise) so a new user sees what each widget will do
 * before their own accounts exist. Every widget marks it clearly as sample data.
 */

export interface BudgetPeriod { start: Date; end: Date; daysLeft: number; daysTotal: number }

/** The current budget period, which starts on the user's chosen day of the month (e.g. payday). */
export function budgetPeriod(startDay: number, today = new Date()): BudgetPeriod {
  const y = today.getFullYear()
  const m = today.getMonth()
  const start = today.getDate() >= startDay ? new Date(y, m, startDay) : new Date(y, m - 1, startDay)
  const end = new Date(start.getFullYear(), start.getMonth() + 1, startDay - 1)
  const day = 86_400_000
  const startOfToday = new Date(y, m, today.getDate())
  return {
    start,
    end,
    daysTotal: Math.round((end.getTime() - start.getTime()) / day) + 1,
    daysLeft: Math.round((end.getTime() - startOfToday.getTime()) / day) + 1,
  }
}

const rupees = (r: number) => Math.round(r * 100)

export const sample = {
  income: rupees(120_000),
  spent: rupees(58_350),
  commitmentsLeft: rupees(31_200),
  savingsGoal: rupees(15_000),

  /** fixed category → series slot, so a colour always means the same category */
  categories: [
    { key: 'rent', name: 'Rent', slot: 1, spent: rupees(28_000) },
    { key: 'shopping', name: 'Shopping', slot: 2, spent: rupees(8_900) },
    { key: 'groceries', name: 'Groceries', slot: 3, spent: rupees(8_640) },
    { key: 'eating', name: 'Eating out', slot: 4, spent: rupees(5_420) },
    { key: 'utilities', name: 'Utilities', slot: 5, spent: rupees(3_980) },
    { key: 'other', name: 'Other', slot: 0, spent: rupees(3_410) },
  ],

  budgets: [
    { name: 'Groceries', spent: rupees(8_640), limit: rupees(12_000) },
    { name: 'Eating out', spent: rupees(5_420), limit: rupees(6_000) },
    { name: 'Shopping', spent: rupees(8_900), limit: rupees(8_000) },
    { name: 'Fuel & transport', spent: rupees(2_150), limit: rupees(5_000) },
    { name: 'Utilities', spent: rupees(3_980), limit: rupees(4_500) },
  ],

  cashFlow: [
    { month: 'May', income: rupees(118_000), spending: rupees(96_400) },
    { month: 'Jun', income: rupees(118_000), spending: rupees(104_900) },
    { month: 'Jul', income: rupees(120_000), spending: rupees(88_200) },
    { month: 'Aug', income: rupees(120_000), spending: rupees(126_300) },
    { month: 'Sep', income: rupees(120_000), spending: rupees(91_750) },
    { month: 'Oct', income: rupees(120_000), spending: rupees(58_350) },
  ],

  transactions: [
    { id: 't1', name: 'Swiggy', category: 'Eating out', amount: -rupees(486), daysAgo: 0, via: 'UPI' },
    { id: 't2', name: 'BESCOM electricity', category: 'Utilities', amount: -rupees(1_240), daysAgo: 1, via: 'Netbanking' },
    { id: 't3', name: 'BigBasket', category: 'Groceries', amount: -rupees(2_315), daysAgo: 1, via: 'Card' },
    { id: 't4', name: 'Uber', category: 'Transport', amount: -rupees(312), daysAgo: 2, via: 'UPI' },
    { id: 't5', name: 'Salary: Acme Corp', category: 'Salary', amount: rupees(120_000), daysAgo: 3, via: 'NEFT' },
    { id: 't6', name: 'Netflix', category: 'Subscriptions', amount: -rupees(649), daysAgo: 4, via: 'Card' },
  ],

  bills: [
    { id: 'b1', name: 'Home loan EMI', amount: rupees(32_000), inDays: 3, kind: 'EMI' },
    { id: 'b2', name: 'HDFC credit card', amount: rupees(23_450), inDays: 6, kind: 'Card due' },
    { id: 'b3', name: 'SIP: Index fund', amount: rupees(5_000), inDays: 8, kind: 'SIP' },
    { id: 'b4', name: 'Airtel fibre', amount: rupees(1_178), inDays: 9, kind: 'Bill' },
    { id: 'b5', name: 'LIC premium (yearly)', amount: rupees(18_500), inDays: 21, kind: 'Insurance' },
  ],

  groups: [
    { id: 'g1', name: 'Goa trip', emoji: '🏖️', balance: rupees(4_250), members: 5 },
    { id: 'g2', name: 'Flat 302', emoji: '🏠', balance: -rupees(1_860), members: 3 },
    { id: 'g3', name: 'Office lunches', emoji: '🍱', balance: 0, members: 8 },
  ],

  goals: [
    { id: 'o1', name: 'Emergency fund', saved: rupees(180_000), target: rupees(300_000), by: 'Dec 2026' },
    { id: 'o2', name: 'Japan trip', saved: rupees(62_000), target: rupees(250_000), by: 'Mar 2027' },
    { id: 'o3', name: 'New laptop', saved: rupees(48_000), target: rupees(90_000), by: 'Jan 2027' },
  ],

  household: {
    name: 'Sharma household',
    spent: rupees(42_300),
    budget: rupees(60_000),
    members: [
      { name: 'Priya', paid: rupees(22_000), share: 0.5 },
      { name: 'Arjun', paid: rupees(20_300), share: 0.5 },
      { name: 'Aarav (son)', paid: 0, share: 0, dependent: true },
    ],
  },
}

export function safeToSpend() {
  return sample.income - sample.spent - sample.commitmentsLeft - sample.savingsGoal
}
