import { describe, expect, it } from 'vitest'
import { formatMoney, formatMoneyShort, formatMoneyWhole, minorToInput, parseMoney } from './money'

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

describe('parseMoney', () => {
  it('reads what people type, in paise', () => {
    expect(parseMoney('486')).toBe(48_600)
    expect(parseMoney('486.5')).toBe(48_650)
    expect(parseMoney('1,00,000')).toBe(1_00_000_00)
    expect(parseMoney(' ₹1,200.75 ')).toBe(1_200_75)
    expect(parseMoney('0.05')).toBe(5)
  })

  it('refuses what is not an amount', () => {
    for (const bad of ['', 'abc', '-5', '1.234', '1.2.3', '12e3']) expect(parseMoney(bad)).toBeNull()
    expect(parseMoney('100', 'JPY')).toBe(100)
    expect(parseMoney('1.5', 'JPY')).toBeNull()
  })

  it('round-trips with minorToInput', () => {
    for (const minor of [0, 5, 48_650, 1_20_000_00]) expect(parseMoney(minorToInput(minor))).toBe(minor)
    expect(minorToInput(48_650)).toBe('486.50')
    expect(minorToInput(1_20_000_00)).toBe('120000')
  })
})
