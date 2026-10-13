/**
 * The maths behind the utilities. Inputs and outputs are rupees (plain numbers), rounded only for display:
 * these are planning tools, unlike the ledger, which keeps exact paise.
 */

// ---------------------------------------------------------------- loans

/** Monthly instalment for a loan: P·r·(1+r)^n / ((1+r)^n − 1), r = annual rate / 12. */
export function emi(principal: number, annualRatePct: number, months: number): number {
  if (principal <= 0 || months <= 0) return 0
  const r = annualRatePct / 12 / 100
  if (r === 0) return principal / months
  const f = Math.pow(1 + r, months)
  return (principal * r * f) / (f - 1)
}

export interface ScheduleYear { year: number; principal: number; interest: number; balance: number }

/**
 * Year-by-year split of the instalments, with an optional extra payment every month (a prepayment shortens the loan).
 * {@code months} is how long it actually takes.
 */
export function amortize(principal: number, annualRatePct: number, months: number, extraMonthly = 0) {
  const r = annualRatePct / 12 / 100
  const pay = emi(principal, annualRatePct, months)
  let balance = principal
  let totalInterest = 0
  let m = 0
  const years: ScheduleYear[] = []
  let y = { year: 1, principal: 0, interest: 0, balance }
  while (balance > 0.005 && m < 1200) {
    const interest = balance * r
    const toPrincipal = Math.min(balance, pay + extraMonthly - interest)
    if (toPrincipal <= 0) break // the payment doesn't even cover the interest
    balance -= toPrincipal
    totalInterest += interest
    y.principal += toPrincipal
    y.interest += interest
    m++
    if (m % 12 === 0 || balance <= 0.005) {
      years.push({ ...y, balance: Math.max(0, balance) })
      y = { year: y.year + 1, principal: 0, interest: 0, balance }
    }
  }
  return { emi: pay, months: m, totalInterest, totalPaid: principal + totalInterest, years }
}

// ---------------------------------------------------------------- savings and investments

/**
 * A SIP invested at the start of each month, compounding monthly at the expected yearly return, with the monthly
 * amount raised by {@code stepUpPct} every year.
 */
export function sip(monthly: number, annualReturnPct: number, years: number, stepUpPct = 0) {
  const r = annualReturnPct / 12 / 100
  let value = 0
  let invested = 0
  let amount = monthly
  for (let m = 0; m < Math.round(years * 12); m++) {
    if (m > 0 && m % 12 === 0) amount *= 1 + stepUpPct / 100
    value = (value + amount) * (1 + r)
    invested += amount
  }
  return { invested, value, gains: value - invested }
}

/** A one-time investment growing at a yearly rate, compounded {@code perYear} times a year (FDs: quarterly). */
export function lumpsum(principal: number, annualRatePct: number, years: number, perYear = 1) {
  const value = principal * Math.pow(1 + annualRatePct / 100 / perYear, perYear * years)
  return { invested: principal, value, gains: value - principal }
}

/** A recurring deposit: a fixed sum every month, interest compounded quarterly (how Indian banks calculate RDs). */
export function recurringDeposit(monthly: number, annualRatePct: number, months: number) {
  const q = annualRatePct / 400
  let value = 0
  for (let k = months; k >= 1; k--) value += monthly * Math.pow(1 + q, k / 3)
  return { invested: monthly * months, value, gains: value - monthly * months }
}

/** What something costing {@code today} will cost after {@code years} of inflation. */
export function futureCost(today: number, inflationPct: number, years: number): number {
  return today * Math.pow(1 + inflationPct / 100, years)
}

// ---------------------------------------------------------------- GST

/** GST 2.0 (from 22 Sept 2025): 5% and 18% for most things, 40% for a short list of luxury and sin goods. */
export const GST_RATES = [0, 5, 18, 40] as const

/**
 * @param inclusive the amount already includes GST (work backwards to the base price)
 * @returns base, tax, total; within one state the tax splits equally into CGST and SGST, between states it's all IGST
 */
export function gst(amount: number, ratePct: number, inclusive: boolean) {
  const base = inclusive ? amount / (1 + ratePct / 100) : amount
  const tax = base * (ratePct / 100)
  return { base, tax, total: base + tax, cgst: tax / 2, sgst: tax / 2 }
}

// ---------------------------------------------------------------- income tax (India)

export type Regime = 'new' | 'old'
export type AgeBand = 'below60' | '60to80' | 'above80'

/**
 * Old regime deductions (the new regime allows only the standard deduction).
 * Each is capped at its legal limit; HRA is the exempt amount the person has worked out.
 */
export interface Deductions { sec80C: number; sec80D: number; nps80CCD1B: number; homeLoanInterest: number; hra: number; other: number }

export const NO_DEDUCTIONS: Deductions = { sec80C: 0, sec80D: 0, nps80CCD1B: 0, homeLoanInterest: 0, hra: 0, other: 0 }

/** FY 2026-27 (AY 2027-28): the new regime is unchanged by Budget 2026; the old regime unchanged for years. */
export const TAX_YEAR = 'FY 2026-27'

const NEW_SLABS: [number, number][] = [[4_00_000, 0], [8_00_000, 5], [12_00_000, 10], [16_00_000, 15], [20_00_000, 20], [24_00_000, 25], [Infinity, 30]]
const oldSlabs = (age: AgeBand): [number, number][] => {
  const nil = age === 'above80' ? 5_00_000 : age === '60to80' ? 3_00_000 : 2_50_000
  const all: [number, number][] = [[nil, 0], [5_00_000, 5], [10_00_000, 20], [Infinity, 30]]
  return all.filter(([upTo], i) => i === 0 || upTo > nil)
}

function slabTax(income: number, slabs: [number, number][]): number {
  let tax = 0
  let from = 0
  for (const [upTo, rate] of slabs) {
    if (income > from) tax += (Math.min(income, upTo) - from) * (rate / 100)
    from = upTo
  }
  return tax
}

/** Surcharge on very high incomes, with marginal relief (crossing a threshold never costs more than the extra income). */
function withSurcharge(taxable: number, tax: number, regime: Regime, slabs: [number, number][]): number {
  const bands: [number, number][] = regime === 'new'
    ? [[5_00_00_000, 25], [2_00_00_000, 25], [1_00_00_000, 15], [50_00_000, 10]]
    : [[5_00_00_000, 37], [2_00_00_000, 25], [1_00_00_000, 15], [50_00_000, 10]]
  const band = bands.find(([threshold]) => taxable > threshold)
  if (!band) return tax
  const [threshold, rate] = band
  const full = tax * (1 + rate / 100)
  const lowerRate = bands.find(([t]) => threshold > t)?.[1] ?? 0
  const atThreshold = slabTax(threshold, slabs) * (1 + lowerRate / 100)
  return Math.min(full, atThreshold + (taxable - threshold))
}

export interface TaxResult {
  regime: Regime
  grossIncome: number
  standardDeduction: number
  deductions: number
  taxable: number
  slabTax: number
  rebate: number
  surcharge: number
  cess: number
  total: number
  /** Total as a share of gross income. */
  effectiveRatePct: number
}

/**
 * Income tax for a resident individual on salary and other ordinary income (not capital gains, which have their own
 * rates). An estimate for planning, not tax advice.
 *
 * @param salaried gets the standard deduction (₹75,000 new regime, ₹50,000 old)
 */
export function incomeTax(grossIncome: number, regime: Regime, opts: { salaried?: boolean; age?: AgeBand; deductions?: Deductions } = {}): TaxResult {
  const salaried = opts.salaried ?? true
  const age = opts.age ?? 'below60'
  const d = opts.deductions ?? NO_DEDUCTIONS
  const standardDeduction = salaried ? Math.min(grossIncome, regime === 'new' ? 75_000 : 50_000) : 0
  const deductions = regime === 'new' ? 0
    : Math.min(d.sec80C, 1_50_000) + Math.min(d.sec80D, 1_00_000) + Math.min(d.nps80CCD1B, 50_000)
      + Math.min(d.homeLoanInterest, 2_00_000) + Math.max(0, d.hra) + Math.max(0, d.other)
  const taxable = Math.max(0, grossIncome - standardDeduction - deductions)
  const slabs = regime === 'new' ? NEW_SLABS : oldSlabs(age)
  const base = slabTax(taxable, slabs)

  // Section 87A: no tax up to ₹12 lakh taxable (new) or ₹5 lakh (old). Just above ₹12 lakh (new), the tax can't be more
  // than the income above ₹12 lakh (marginal relief).
  let rebate = 0
  if (regime === 'new') {
    if (taxable <= 12_00_000) rebate = Math.min(base, 60_000)
    else if (base > taxable - 12_00_000) rebate = base - (taxable - 12_00_000)
  } else if (taxable <= 5_00_000) {
    rebate = Math.min(base, 12_500)
  }
  const afterRebate = Math.max(0, base - rebate)
  const withSur = withSurcharge(taxable, afterRebate, regime, slabs)
  const surcharge = withSur - afterRebate
  const cess = withSur * 0.04
  const total = withSur + cess
  return {
    regime, grossIncome, standardDeduction, deductions, taxable, slabTax: base, rebate, surcharge, cess, total,
    effectiveRatePct: grossIncome > 0 ? (total / grossIncome) * 100 : 0,
  }
}

// ---------------------------------------------------------------- words

const ONES = ['', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten', 'eleven', 'twelve', 'thirteen',
  'fourteen', 'fifteen', 'sixteen', 'seventeen', 'eighteen', 'nineteen']
const TENS = ['', '', 'twenty', 'thirty', 'forty', 'fifty', 'sixty', 'seventy', 'eighty', 'ninety']

function below100(n: number): string {
  return n < 20 ? ONES[n]! : `${TENS[Math.floor(n / 10)]}${n % 10 ? `-${ONES[n % 10]}` : ''}`
}

function below1000(n: number): string {
  const h = Math.floor(n / 100)
  const rest = n % 100
  return [h ? `${ONES[h]} hundred` : '', rest ? below100(rest) : ''].filter(Boolean).join(' ')
}

/** Indian style, for cheques: 1,50,250.50 -> "Rupees one lakh fifty thousand two hundred fifty and fifty paise only". */
export function amountInWords(amount: number): string {
  if (!Number.isFinite(amount) || amount < 0 || amount >= 1e15) return ''
  const rupees = Math.floor(amount + 1e-9)
  const paise = Math.round((amount - rupees) * 100)
  const parts: string[] = []
  let n = rupees
  const crore = Math.floor(n / 1e7); n %= 1e7
  const lakh = Math.floor(n / 1e5); n %= 1e5
  const thousand = Math.floor(n / 1e3); n %= 1e3
  if (crore) parts.push(`${crore >= 1000 ? amountInWords(crore).replace(/^Rupees |\s*only$/g, '') : below1000(crore)} crore`)
  if (lakh) parts.push(`${below100(lakh)} lakh`)
  if (thousand) parts.push(`${below100(thousand)} thousand`)
  if (n) parts.push(below1000(n))
  const words = parts.join(' ') || 'zero'
  return `Rupees ${words}${paise ? ` and ${below100(paise)} paise` : ''} only`
}
