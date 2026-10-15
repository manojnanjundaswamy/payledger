/**
 * Money is integer minor units (cents, paise, fils) everywhere, exactly like the ledger.
 * Formatting uses the user's locale, so en-IN gets lakh/crore grouping: ₹1,00,000.00.
 */

/**
 * The currency and number format to use when a call doesn't say: the space on screen's (set by the app shell from
 * the profile). Calls about something with its own currency (an account, a group) pass it.
 */
const defaults = { currency: 'INR', locale: 'en-IN' }

export function setMoneyDefaults(currency: string, locale: string) {
  defaults.currency = currency
  defaults.locale = locale
}

export function moneyDefaults(): Readonly<typeof defaults> {
  return defaults
}

/** "." or ",": how this locale writes the decimal point. */
function decimalMark(locale: string): '.' | ',' {
  try {
    return new Intl.NumberFormat(locale).formatToParts(1.5).find((p) => p.type === 'decimal')?.value === ',' ? ',' : '.'
  } catch {
    return '.'
  }
}

/** "₹", "$", "AED": how a currency is written in this locale, for amount boxes. */
export function currencySymbol(currency = defaults.currency, locale = defaults.locale): string {
  try {
    return new Intl.NumberFormat(locale, { style: 'currency', currency, currencyDisplay: 'narrowSymbol' })
      .formatToParts(0).find((p) => p.type === 'currency')?.value ?? currency
  } catch {
    return currency
  }
}

/** Every currency the browser knows (ISO 4217), for pickers: [{ code, name }]. */
export function allCurrencies(locale = defaults.locale): { code: string; name: string }[] {
  const codes = typeof Intl.supportedValuesOf === 'function' ? Intl.supportedValuesOf('currency') : ['INR', 'USD', 'EUR', 'GBP', 'AED']
  let names: Intl.DisplayNames | null = null
  try { names = new Intl.DisplayNames([locale], { type: 'currency' }) } catch { /* names are optional */ }
  return codes.map((code) => ({ code, name: names?.of(code) ?? code }))
}
export function formatMoney(minor: number, currency = defaults.currency, locale = defaults.locale, opts: { compact?: boolean; sign?: boolean } = {}): string {
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
export function formatMoneyWhole(minor: number, currency = defaults.currency, locale = defaults.locale): string {
  const value = minor / 10 ** fractionDigits(currency)
  return new Intl.NumberFormat(locale, { style: 'currency', currency, maximumFractionDigits: 0 }).format(value)
}

/**
 * Short amounts for axes and tight spaces. Indian locales get K / L (lakh) / Cr (crore) instead
 * of Intl's en-IN "T" for thousand, which nobody reads as thousand.
 */
export function formatMoneyShort(minor: number, currency = defaults.currency, locale = defaults.locale): string {
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
export function parseMoney(text: string, currency = defaults.currency, locale = defaults.locale): number | null {
  const digits = fractionDigits(currency)
  if (/-/.test(text)) return null
  // The currency written before or after ("AED 1,200", "1 234,50 €", "₹500") goes; inside, only digits and separators.
  const core = text.trim().replace(/^[^\d.,]+/, '').replace(/[^\d.,]+$/, '').replace(/[\s  ']/g, '')
  if (!core || /[^\d.,]/.test(core)) return null
  // The decimal mark is the one of the person's number format ("." in en-IN/en-US, "," in de-DE); the other is
  // grouping. "1200.50" in a "," format is still read as a decimal: one dot, no comma, at most the currency's decimals.
  let decimal = decimalMark(locale)
  if (decimal === ',' && !core.includes(',') && (core.match(/\./g) ?? []).length === 1 && core.length - core.indexOf('.') - 1 <= digits) decimal = '.'
  const normal = decimal === '.' ? core.replace(/,/g, '') : core.replace(/\./g, '').replace(',', '.')
  const match = /^(\d+)(?:\.(\d*))?$/.exec(normal)
  if (!match || (match[2] ?? '').length > digits) return null
  const minor = Number(match[1]) * 10 ** digits + Number((match[2] ?? '').padEnd(digits, '0') || 0)
  return Number.isSafeInteger(minor) ? minor : null
}

/** The other way, for editing: 120000 -> "1200", 48650 -> "486.50" (no grouping, so it parses back). */
export function minorToInput(minor: number, currency = defaults.currency): string {
  const digits = fractionDigits(currency)
  const whole = Math.trunc(minor / 10 ** digits)
  const rest = Math.abs(minor % 10 ** digits)
  return rest === 0 ? String(whole) : `${whole}.${String(rest).padStart(digits, '0')}`
}
