import { useMemo, useState, type FormEvent } from 'react'
import { motion } from 'motion/react'
import { useQueryClient } from '@tanstack/react-query'
import { ArrowRight, CalendarDays, Clock, Globe2, Wallet } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/controls'
import { SearchSelect } from '@/components/ui/search-select'
import { ApiError } from '@/lib/api'
import { formatMoney } from '@/lib/money'
import { CURRENCIES, LOCALES, MONTHS, REGIONS, guessRegion, regionOf } from '@/lib/regions'
import { Field, Select } from '@/pages/money/parts'
import { useProfile, useUpdateProfile } from '@/state/profile'

const ordinal = (n: number) => n + (['th', 'st', 'nd', 'rd'][(n % 100 - 20) % 10] ?? ['th', 'st', 'nd', 'rd'][n % 100] ?? 'th')

/**
 * The things PayLedger must know before it can be right about money: where you are (currency, number format, time
 * zone), when your financial year starts, and the day your month starts (payday). Asked at sign-in until saved.
 */
export function RegionSetup({ onDone, onLater }: { onDone: () => void; onLater: () => void }) {
  const { data: profile } = useProfile()
  const update = useUpdateProfile()
  const client = useQueryClient()
  const start = regionOf(profile?.settings.country) ?? guessRegion()
  const [name, setName] = useState(profile?.displayName ?? '')
  const [country, setCountry] = useState(start.code)
  const [currency, setCurrency] = useState(profile?.settings.country ? profile.settings.currency : start.currency)
  const [locale, setLocale] = useState(profile?.settings.country ? profile.settings.locale : start.locale)
  const [timeZone, setTimeZone] = useState(profile?.settings.country ? profile.settings.timeZone : (Intl.DateTimeFormat().resolvedOptions().timeZone || start.timeZone))
  const [fy, setFy] = useState(profile?.settings.country ? (profile.settings.fyStartMonth ?? start.fyStartMonth) : start.fyStartMonth)
  const [payday, setPayday] = useState(profile?.settings.budgetStartDay ?? 1)
  const [error, setError] = useState<string>()
  const zones = useMemo(() => (typeof Intl.supportedValuesOf === 'function' ? Intl.supportedValuesOf('timeZone') : [timeZone]), [timeZone])

  function chooseCountry(code: string) {
    const r = regionOf(code)
    setCountry(code)
    if (!r) return
    setCurrency(r.currency)
    setLocale(r.locale)
    setTimeZone(r.timeZone)
    setFy(r.fyStartMonth)
  }

  async function save(e: FormEvent) {
    e.preventDefault()
    if (!name.trim()) return setError('What should we call you?')
    setError(undefined)
    try {
      await update.mutateAsync({ displayName: name.trim(), country, currency, locale, timeZone, fyStartMonth: fy, budgetStartDay: payday })
      // A new space follows the currency: everything showing money reloads.
      void client.invalidateQueries()
      onDone()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not save. Try again.')
    }
  }

  const fyEnd = MONTHS[(fy + 10) % 12]
  return (
    <motion.div role="dialog" aria-modal aria-label="Set up your region" data-testid="region-setup"
      initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
      className="fixed inset-0 z-[70] overflow-y-auto bg-bg/95 backdrop-blur-xl">
      <form onSubmit={save} className="mx-auto flex min-h-full max-w-xl flex-col gap-5 px-4 py-8 sm:py-12">
        <div className="flex flex-col items-center gap-2 text-center">
          <motion.span initial={{ scale: 0.5, rotate: -30 }} animate={{ scale: 1, rotate: 0 }} transition={{ type: 'spring', stiffness: 260, damping: 14 }}
            className="flex size-14 items-center justify-center rounded-2xl bg-accent text-accent-fg shadow-lift"><Globe2 className="size-7" /></motion.span>
          <h2 className="text-2xl font-semibold tracking-tight">First, a few things about you</h2>
          <p className="max-w-md text-sm text-muted">So every amount, date and month is right for where you live. It takes a minute, and you can change any of it later in Settings.</p>
        </div>

        <section className="flex flex-col gap-4 rounded-card border border-border bg-surface p-pad shadow-card">
          <Field label="Your name" htmlFor="region-name">
            <Input id="region-name" value={name} onChange={(e) => setName(e.target.value)} maxLength={100} autoComplete="name" />
          </Field>
          <Field label="Where you live" htmlFor="region-country" hint="Sets the rest; change any of it below.">
            <SearchSelect id="region-country" aria-label="Where you live" value={country} onChange={chooseCountry}
              options={REGIONS.map((r) => ({ value: r.code, label: r.name, icon: <span aria-hidden>{r.flag}</span>, keywords: `${r.code} ${r.currency}` }))} />
          </Field>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Currency" htmlFor="region-currency">
              <Select id="region-currency" value={currency} onChange={(e) => setCurrency(e.target.value)}>
                {[...new Set([currency, ...CURRENCIES])].map((c) => <option key={c} value={c}>{c}</option>)}
              </Select>
            </Field>
            <Field label="Number format" htmlFor="region-locale">
              <Select id="region-locale" value={locale} onChange={(e) => setLocale(e.target.value)}>
                {[...LOCALES.filter((l) => l.value === locale).length ? [] : [{ value: locale, label: locale }], ...LOCALES].map((l) => <option key={l.value} value={l.value}>{l.label}</option>)}
              </Select>
            </Field>
            <Field label="Time zone" htmlFor="region-zone">
              <Select id="region-zone" value={timeZone} onChange={(e) => setTimeZone(e.target.value)}>
                {[...new Set([timeZone, ...zones])].map((z) => <option key={z} value={z}>{z.replace(/_/g, ' ')}</option>)}
              </Select>
            </Field>
            <Field label="Financial year starts" htmlFor="region-fy" hint={`${MONTHS[fy - 1]} to ${fyEnd}`}>
              <Select id="region-fy" value={String(fy)} onChange={(e) => setFy(Number(e.target.value))}>
                {MONTHS.map((m, i) => <option key={m} value={i + 1}>{m}</option>)}
              </Select>
            </Field>
          </div>
          <Field label="Your month starts on" htmlFor="region-payday" hint="Your payday, so budgets follow your salary. The 1st for a calendar month.">
            <Select id="region-payday" value={String(payday)} onChange={(e) => setPayday(Number(e.target.value))}>
              {Array.from({ length: 28 }, (_, i) => <option key={i + 1} value={i + 1}>{i === 0 ? '1st (calendar month)' : `${ordinal(i + 1)} of the month`}</option>)}
            </Select>
          </Field>
          <ul className="grid gap-2 rounded-control bg-surface-2 p-3 text-xs text-muted sm:grid-cols-3" aria-label="How it will look">
            <li className="flex items-center gap-1.5"><Wallet className="size-3.5 shrink-0 text-accent" /><span className="tabular text-fg">{safeFormat(12_34_567_89, currency, locale)}</span></li>
            <li className="flex items-center gap-1.5"><CalendarDays className="size-3.5 shrink-0 text-accent" />Year: {MONTHS[fy - 1]!.slice(0, 3)}–{fyEnd!.slice(0, 3)}</li>
            <li className="flex items-center gap-1.5"><Clock className="size-3.5 shrink-0 text-accent" />Month from the {ordinal(payday)}</li>
          </ul>
        </section>

        {error && <p className="text-sm text-critical" role="alert">{error}</p>}
        <div className="flex flex-wrap items-center justify-end gap-2">
          <Button variant="ghost" type="button" onClick={onLater}>Later</Button>
          <Button variant="primary" size="lg" type="submit" disabled={update.isPending}>{update.isPending ? 'Saving…' : 'Save and continue'}<ArrowRight /></Button>
        </div>
      </form>
    </motion.div>
  )
}

function safeFormat(minor: number, currency: string, locale: string) {
  try { return formatMoney(minor, currency, locale) } catch { return formatMoney(minor, currency, 'en-US') }
}
