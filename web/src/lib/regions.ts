/**
 * Where someone lives decides the sensible defaults: currency, number format, time zone and when the financial year
 * starts. Every one stays changeable; "ZZ" is somewhere not listed (choose each yourself).
 */
export interface Region { code: string; name: string; flag: string; currency: string; locale: string; timeZone: string; fyStartMonth: number }

export const REGIONS: Region[] = [
  { code: 'IN', name: 'India', flag: '🇮🇳', currency: 'INR', locale: 'en-IN', timeZone: 'Asia/Kolkata', fyStartMonth: 4 },
  { code: 'AE', name: 'United Arab Emirates', flag: '🇦🇪', currency: 'AED', locale: 'en-AE', timeZone: 'Asia/Dubai', fyStartMonth: 1 },
  { code: 'SA', name: 'Saudi Arabia', flag: '🇸🇦', currency: 'SAR', locale: 'en-SA', timeZone: 'Asia/Riyadh', fyStartMonth: 1 },
  { code: 'QA', name: 'Qatar', flag: '🇶🇦', currency: 'QAR', locale: 'en-QA', timeZone: 'Asia/Qatar', fyStartMonth: 1 },
  { code: 'KW', name: 'Kuwait', flag: '🇰🇼', currency: 'KWD', locale: 'en-KW', timeZone: 'Asia/Kuwait', fyStartMonth: 1 },
  { code: 'OM', name: 'Oman', flag: '🇴🇲', currency: 'OMR', locale: 'en-OM', timeZone: 'Asia/Muscat', fyStartMonth: 1 },
  { code: 'BH', name: 'Bahrain', flag: '🇧🇭', currency: 'BHD', locale: 'en-BH', timeZone: 'Asia/Bahrain', fyStartMonth: 1 },
  { code: 'SG', name: 'Singapore', flag: '🇸🇬', currency: 'SGD', locale: 'en-SG', timeZone: 'Asia/Singapore', fyStartMonth: 1 },
  { code: 'MY', name: 'Malaysia', flag: '🇲🇾', currency: 'MYR', locale: 'en-MY', timeZone: 'Asia/Kuala_Lumpur', fyStartMonth: 1 },
  { code: 'US', name: 'United States', flag: '🇺🇸', currency: 'USD', locale: 'en-US', timeZone: 'America/New_York', fyStartMonth: 1 },
  { code: 'CA', name: 'Canada', flag: '🇨🇦', currency: 'CAD', locale: 'en-CA', timeZone: 'America/Toronto', fyStartMonth: 1 },
  { code: 'GB', name: 'United Kingdom', flag: '🇬🇧', currency: 'GBP', locale: 'en-GB', timeZone: 'Europe/London', fyStartMonth: 4 },
  { code: 'IE', name: 'Ireland', flag: '🇮🇪', currency: 'EUR', locale: 'en-IE', timeZone: 'Europe/Dublin', fyStartMonth: 1 },
  { code: 'DE', name: 'Germany', flag: '🇩🇪', currency: 'EUR', locale: 'de-DE', timeZone: 'Europe/Berlin', fyStartMonth: 1 },
  { code: 'NL', name: 'Netherlands', flag: '🇳🇱', currency: 'EUR', locale: 'nl-NL', timeZone: 'Europe/Amsterdam', fyStartMonth: 1 },
  { code: 'FR', name: 'France', flag: '🇫🇷', currency: 'EUR', locale: 'fr-FR', timeZone: 'Europe/Paris', fyStartMonth: 1 },
  { code: 'AU', name: 'Australia', flag: '🇦🇺', currency: 'AUD', locale: 'en-AU', timeZone: 'Australia/Sydney', fyStartMonth: 7 },
  { code: 'NZ', name: 'New Zealand', flag: '🇳🇿', currency: 'NZD', locale: 'en-NZ', timeZone: 'Pacific/Auckland', fyStartMonth: 4 },
  { code: 'JP', name: 'Japan', flag: '🇯🇵', currency: 'JPY', locale: 'ja-JP', timeZone: 'Asia/Tokyo', fyStartMonth: 4 },
  { code: 'LK', name: 'Sri Lanka', flag: '🇱🇰', currency: 'LKR', locale: 'en-LK', timeZone: 'Asia/Colombo', fyStartMonth: 4 },
  { code: 'NP', name: 'Nepal', flag: '🇳🇵', currency: 'NPR', locale: 'en-NP', timeZone: 'Asia/Kathmandu', fyStartMonth: 7 },
  { code: 'BD', name: 'Bangladesh', flag: '🇧🇩', currency: 'BDT', locale: 'en-BD', timeZone: 'Asia/Dhaka', fyStartMonth: 7 },
  { code: 'ZZ', name: 'Somewhere else', flag: '🌍', currency: 'USD', locale: 'en-US', timeZone: 'UTC', fyStartMonth: 1 },
]

export const CURRENCIES = [...new Set(REGIONS.map((r) => r.currency))]

export const LOCALES = [
  { value: 'en-IN', label: 'English (India): 1,00,000.00' },
  { value: 'hi-IN', label: 'हिन्दी (भारत)' },
  { value: 'en-US', label: 'English (US): 100,000.00' },
  { value: 'en-GB', label: 'English (UK): 100,000.00' },
  { value: 'en-AE', label: 'English (UAE)' },
  { value: 'en-SG', label: 'English (Singapore)' },
  { value: 'en-AU', label: 'English (Australia)' },
  { value: 'de-DE', label: 'Deutsch: 100.000,00' },
  { value: 'fr-FR', label: 'Français: 100 000,00' },
  ...REGIONS.filter((r) => !['en-IN', 'en-US', 'en-GB', 'en-AE', 'en-SG', 'en-AU', 'de-DE', 'fr-FR'].includes(r.locale))
    .map((r) => ({ value: r.locale, label: `${r.name} format` })),
]

export const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December']

/** The region a browser seems to be in, from its time zone (a guess to start from; the person confirms it). */
export function guessRegion(): Region {
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone
  return REGIONS.find((r) => r.timeZone === zone)
    ?? (zone === 'Asia/Calcutta' ? REGIONS[0]! : zone?.startsWith('America/') ? REGIONS.find((r) => r.code === 'US')!
      : zone?.startsWith('Australia/') ? REGIONS.find((r) => r.code === 'AU')! : REGIONS[0]!)
}

export function regionOf(code: string | null | undefined): Region | undefined {
  return REGIONS.find((r) => r.code === code)
}
