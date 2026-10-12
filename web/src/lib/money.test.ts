import { describe, expect, it } from 'vitest'
import { formatMoney, formatMoneyShort, formatMoneyWhole } from './money'

describe('formatMoney', () => {
  it('uses lakh and crore grouping for en-IN', () => {
    expect(formatMoney(1_00_000_00)).toBe('₹1,00,000.00')
    expect(formatMoneyWhole(2_50_00_000_00)).toBe('₹2,50,00,000')
  })

  it('shortens with K, L and Cr in India', () => {
    expect(formatMoneyShort(50_000_00)).toBe('₹50K')
    expect(formatMoneyShort(1_50_000_00)).toBe('₹1.5L')
    expect(formatMoneyShort(2_50_00_000_00)).toBe('₹2.5Cr')
    expect(formatMoneyShort(0)).toBe('₹0')
    expect(formatMoneyShort(1_500_000_00, 'USD', 'en-US')).toBe('$1.5M')
  })

  it('handles signs and other locales', () => {
    expect(formatMoney(-2_500_00, 'INR', 'en-IN', { sign: true })).toBe('-₹2,500.00')
    expect(formatMoney(1_000_000_00, 'USD', 'en-US')).toBe('$1,000,000.00')
  })
})
