/** The two plans as people see them (docs/plan/market-and-pricing.md); core-service enforces the limits. */

export const PRICE = { monthly: '₹99', yearly: '₹799', yearlySaving: 'save 33%' }

export interface PlanLine { feature: string; free: string | boolean; premium: string | boolean }

export const PLAN_LINES: PlanLine[] = [
  { feature: 'Accounts, transactions, categories', free: 'Up to 5 open accounts', premium: 'Unlimited' },
  { feature: 'Budgets with status and spending pace', free: 'Up to 5', premium: 'Unlimited' },
  { feature: 'Bills, EMIs and SIPs, safe to spend', free: 'Up to 5', premium: 'Unlimited' },
  { feature: 'Big yearly costs (annual planner)', free: '1', premium: 'Unlimited' },
  { feature: 'Import from Excel or CSV', free: '3 a month', premium: 'Unlimited' },
  { feature: 'Reports, incl. April–March financial year', free: true, premium: true },
  { feature: 'Export and your full history', free: true, premium: true },
  { feature: 'Split with friends, guests join by link', free: true, premium: true },
  { feature: 'Household: one budget for up to 6 people', free: false, premium: 'Coming soon' },
  { feature: 'Bank sync and statement parsing', free: false, premium: 'Coming later' },
]

/** core-service's limit keys, as people read them. */
export const LIMIT_LABELS: Record<string, string> = {
  accounts: 'Open accounts',
  budgets: 'Budgets',
  bills: 'Bills and savings on your plan',
  'big-costs': 'Big yearly costs',
  imports: 'Imports this month',
  goals: 'Savings goals',
}
