/**
 * Money is integer minor units (paise) everywhere, exactly like the ledger.
 * Formatting uses the user's locale, so en-IN gets lakh/crore grouping: ₹1,00,000.00.
 */
export function formatMoney(minor: number, currency = 'INR', locale = 'en-IN', opts: { compact?: boolean; sign?: boolean } = {}): string {
  const digits = fractionDigits(currency)
  const value = minor / 10 ** digits
  return new Intl.NumberFormat(locale, {
    style: 'currency',
    currency,
    notation: opts.compact ? 'compact' : 'standard',
    maximumFractionDigits: opts.compact ? 1 : digits,
    minimumFractionDigits: opts.compact ? 0 : digits,
    signDisplay: opts.sign ? 'exceptZero' : 'auto',
  }).format(value)
}

/** Whole units, no decimals: for headline numbers where paise are noise. */
export function formatMoneyWhole(minor: number, currency = 'INR', locale = 'en-IN'): string {
  const value = minor / 10 ** fractionDigits(currency)
  return new Intl.NumberFormat(locale, { style: 'currency', currency, maximumFractionDigits: 0 }).format(value)
}

/**
 * Short amounts for axes and tight spaces. Indian locales get K / L (lakh) / Cr (crore) instead
 * of Intl's en-IN "T" for thousand, which nobody reads as thousand.
 */
export function formatMoneyShort(minor: number, currency = 'INR', locale = 'en-IN'): string {
  const value = minor / 10 ** fractionDigits(currency)
  if (!locale.endsWith('-IN')) {
    return new Intl.NumberFormat(locale, { style: 'currency', currency, notation: 'compact', maximumFractionDigits: 1 }).format(value)
  }
  const symbol = new Intl.NumberFormat(locale, { style: 'currency', currency, maximumFractionDigits: 0 }).formatToParts(0)
    .find((p) => p.type === 'currency')?.value ?? ''
  const abs = Math.abs(value)
  const [div, unit] = abs >= 1e7 ? [1e7, 'Cr'] : abs >= 1e5 ? [1e5, 'L'] : abs >= 1e3 ? [1e3, 'K'] : [1, '']
  const n = (abs / div).toLocaleString(locale, { maximumFractionDigits: 1 })
  return `${value < 0 ? '-' : ''}${symbol}${n}${unit}`
}

export function fractionDigits(currency: string): number {
  return new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions().maximumFractionDigits ?? 2
}

/**
 * Reads an amount someone typed ("1,200.50", "₹1,00,000", "1200") into minor units.
 * Grouping commas and the currency symbol are ignored; returns null for anything that isn't
 * a non-negative amount with at most the currency's decimals.
 */
export function parseMoney(text: string, currency = 'INR'): number | null {
  const digits = fractionDigits(currency)
  const cleaned = text.replace(/[\s,₹$€£]/g, '')
  const match = /^(\d+)(?:\.(\d*))?$/.exec(cleaned)
  if (!match || (match[2] ?? '').length > digits) return null
  const minor = Number(match[1]) * 10 ** digits + Number((match[2] ?? '').padEnd(digits, '0') || 0)
  return Number.isSafeInteger(minor) ? minor : null
}

/** The other way, for editing: 120000 -> "1200", 48650 -> "486.50" (no grouping, so it parses back). */
export function minorToInput(minor: number, currency = 'INR'): string {
  const digits = fractionDigits(currency)
  const whole = Math.trunc(minor / 10 ** digits)
  const rest = Math.abs(minor % 10 ** digits)
  return rest === 0 ? String(whole) : `${whole}.${String(rest).padStart(digits, '0')}`
}
