import { describe, expect, it } from 'vitest'
import { evaluate, ExprError } from './expr'
import { amortize, amountInWords, emi, futureCost, gst, incomeTax, lumpsum, recurringDeposit, sip } from './finance'

describe('calculator expressions', () => {
  it('follows the usual order and brackets', () => {
    expect(evaluate('2 + 3 * 4')).toBe(14)
    expect(evaluate('(2 + 3) * 4')).toBe(20)
    expect(evaluate('2^3^2')).toBe(512)
    expect(evaluate('-3^2')).toBe(-9)
    expect(evaluate('2(3+4)')).toBe(14)
    expect(evaluate('10 mod 3')).toBe(1)
    expect(evaluate('5!')).toBe(120)
    expect(evaluate('0.1 + 0.2')).toBe(0.3)
    expect(evaluate('2^-1')).toBe(0.5)
    expect(evaluate('-2^2 + 10')).toBe(6)
  })

  it('reads Indian amounts the way people write them', () => {
    expect(evaluate('1,50,000 + 25,000')).toBe(175000)
    expect(evaluate('1.5L')).toBe(150000)
    expect(evaluate('2 cr / 12')).toBeCloseTo(1666666.6667, 3)
    expect(evaluate('12k * 12')).toBe(144000)
  })

  it('does percentages like a calculator', () => {
    expect(evaluate('200 + 10%')).toBe(220)
    expect(evaluate('200 - 10%')).toBe(180)
    expect(evaluate('18% * 5000')).toBe(900)
    expect(evaluate('50%')).toBe(0.5)
  })

  it('has scientific functions in degrees or radians, and the last answer', () => {
    expect(evaluate('sin(30)')).toBeCloseTo(0.5)
    expect(evaluate('sin(pi/2)', 'rad')).toBeCloseTo(1)
    expect(evaluate('sqrt(144) + log(1000) + ln(e)')).toBe(16)
    expect(evaluate('ans * 2', 'deg', 21)).toBe(42)
  })

  it('explains mistakes instead of guessing', () => {
    expect(() => evaluate('(2 + 3')).toThrow(ExprError)
    expect(() => evaluate('2 / 0')).toThrow('divide by zero')
    expect(() => evaluate('alert(1)')).toThrow(ExprError)
    expect(() => evaluate('2 +')).toThrow(ExprError)
  })
})

describe('loans', () => {
  it('works out the EMI', () => {
    // ₹50 lakh at 8.5% for 20 years: about ₹43,391.
    expect(Math.round(emi(50_00_000, 8.5, 240))).toBe(43391)
    expect(emi(1_20_000, 0, 12)).toBe(10_000)
  })

  it('splits it into principal and interest, and a prepayment shortens the loan', () => {
    const plain = amortize(10_00_000, 9, 120)
    expect(plain.months).toBe(120)
    expect(plain.years).toHaveLength(10)
    expect(plain.years.at(-1)!.balance).toBeCloseTo(0, 2)
    expect(plain.totalPaid).toBeCloseTo(plain.emi * 120, 0)
    const faster = amortize(10_00_000, 9, 120, 5_000)
    expect(faster.months).toBeLessThan(90)
    expect(faster.totalInterest).toBeLessThan(plain.totalInterest)
  })
})

describe('savings', () => {
  it('grows a SIP, with a yearly step-up', () => {
    // ₹10,000 a month at 12% for 10 years: about ₹23.2 lakh.
    const s = sip(10_000, 12, 10)
    expect(s.invested).toBe(12_00_000)
    expect(Math.round(s.value / 1000)).toBe(2323)
    expect(sip(10_000, 12, 10, 10).value).toBeGreaterThan(s.value)
  })

  it('compounds FDs quarterly and RDs the bank way', () => {
    expect(Math.round(lumpsum(1_00_000, 7, 5, 4).value)).toBe(141478)
    // ₹5,000 a month for 12 months at 7%, quarterly compounding: about ₹62,310.
    expect(recurringDeposit(5_000, 7, 12).value).toBeGreaterThan(62_300)
    expect(recurringDeposit(5_000, 7, 12).value).toBeLessThan(62_320)
    expect(Math.round(futureCost(1_00_000, 6, 10))).toBe(179085)
  })
})

describe('GST', () => {
  it('adds or takes out the tax, split into CGST and SGST', () => {
    expect(gst(1000, 18, false)).toMatchObject({ base: 1000, tax: 180, total: 1180, cgst: 90, sgst: 90 })
    const back = gst(1180, 18, true)
    expect(back.base).toBeCloseTo(1000)
    expect(back.tax).toBeCloseTo(180)
  })
})

describe('income tax, FY 2026-27', () => {
  it('charges nothing up to ₹12.75 lakh salary in the new regime', () => {
    expect(incomeTax(12_75_000, 'new').total).toBe(0)
  })

  it('gives marginal relief just above ₹12 lakh taxable', () => {
    // ₹12.85 lakh salary -> ₹12.10 lakh taxable: tax is the ₹10,000 above ₹12 lakh, plus 4% cess.
    expect(incomeTax(12_85_000, 'new').total).toBeCloseTo(10_400)
  })

  it('follows the new slabs above the rebate', () => {
    // ₹20 lakh salary -> ₹19.25 lakh taxable: 20k + 40k + 60k + 65k (20% of 3.25L) = ₹1,85,000 + 4% cess.
    const t = incomeTax(20_00_000, 'new')
    expect(t.taxable).toBe(19_25_000)
    expect(t.slabTax).toBe(1_85_000)
    expect(t.total).toBe(1_92_400)
  })

  it('takes old-regime deductions, capped at their limits', () => {
    const d = { sec80C: 2_00_000, sec80D: 25_000, nps80CCD1B: 50_000, homeLoanInterest: 2_00_000, hra: 0, other: 0 }
    const t = incomeTax(15_00_000, 'old', { deductions: d })
    // 15L - 50k standard - 1.5L 80C (capped) - 25k - 50k - 2L = ₹10,25,000 taxable.
    expect(t.taxable).toBe(10_25_000)
    // 12,500 + 1,00,000 + 30% of 25,000 = ₹1,20,000 + 4% cess.
    expect(t.total).toBe(1_24_800)
  })

  it('has the old regime rebate up to ₹5 lakh and higher exemptions for seniors', () => {
    expect(incomeTax(5_50_000, 'old').total).toBe(0)
    expect(incomeTax(8_00_000, 'old', { age: 'above80' }).slabTax).toBeLessThan(incomeTax(8_00_000, 'old').slabTax)
  })

  it('adds surcharge with marginal relief on very high incomes', () => {
    const just = incomeTax(50_75_001 + 75_000, 'new')
    const below = incomeTax(50_75_000, 'new')
    // Crossing ₹50 lakh taxable costs at most the extra income (plus cess on it).
    expect(just.total - below.total).toBeLessThan(80_000)
    expect(just.surcharge).toBeGreaterThan(0)
  })
})

describe('amount in words', () => {
  it('writes cheques the Indian way', () => {
    expect(amountInWords(150250.5)).toBe('Rupees one lakh fifty thousand two hundred fifty and fifty paise only')
    expect(amountInWords(2_35_00_000)).toBe('Rupees two crore thirty-five lakh only')
    expect(amountInWords(0)).toBe('Rupees zero only')
  })
})
