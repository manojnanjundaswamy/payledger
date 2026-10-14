import { useState } from 'react'
import { ExternalLink, LogOut, RotateCcw, Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input, Switch } from '@/components/ui/controls'
import { budgetPeriod } from '@/dashboard/sample'
import { formatMoney } from '@/lib/money'
import { parsePreferences } from '@/state/preferences'
import { usePreferences, useUndoable } from '@/state/PreferencesProvider'
import { useProfile, useUpdateProfile, type ProfileUpdate } from '@/state/profile'
import { signOut, useSession } from '@/state/session'
import { UPI_ID } from '@/lib/upi'
import { Field, Section, Select } from './parts'

function useSaveSetting() {
  const update = useUpdateProfile()
  return (patch: ProfileUpdate, what: string) =>
    update.mutate(patch, {
      onSuccess: () => toast.success(`${what} saved`),
      onError: (e) => toast.error(`Couldn't save ${what.toLowerCase()}`, { description: e.message }),
    })
}

export function ProfileSettings() {
  const { data: profile } = useProfile()
  const save = useSaveSetting()
  const [name, setName] = useState(profile?.displayName ?? '')
  const [upi, setUpi] = useState(profile?.settings.upiId ?? '')
  if (!profile) return null
  const upiOk = upi.trim() === '' || UPI_ID.test(upi.trim())
  return (
    <Section title="Profile" description="How you appear to people you share groups and households with.">
      <Field label="Display name" htmlFor="display-name">
        <form className="flex w-full gap-2 sm:w-auto" onSubmit={(e) => { e.preventDefault(); if (name.trim()) save({ displayName: name.trim() }, 'Name') }}>
          <Input id="display-name" value={name} maxLength={100} onChange={(e) => setName(e.target.value)} className="sm:w-56" />
          <Button type="submit" disabled={!name.trim() || name.trim() === profile.displayName}>Save</Button>
        </form>
      </Field>
      <Field label="UPI ID" htmlFor="upi-id" hint="Friends who owe you in a group get a one-tap Pay button. Only people you share a group with see it.">
        <form className="flex w-full flex-col gap-1 sm:w-auto" onSubmit={(e) => { e.preventDefault(); if (upiOk) save({ upiId: upi.trim() }, 'UPI ID') }}>
          <span className="flex gap-2">
            <Input id="upi-id" value={upi} maxLength={100} placeholder="name@okhdfcbank" onChange={(e) => setUpi(e.target.value)} className="sm:w-56" aria-invalid={!upiOk} />
            <Button type="submit" disabled={!upiOk || upi.trim() === (profile.settings.upiId ?? '')}>Save</Button>
          </span>
          {!upiOk && <span className="text-xs text-critical">That doesn't look like a UPI ID (name@bank).</span>}
        </form>
      </Field>
      <Field label="Email" hint="Your sign-in email. Change it under Account.">
        <span className="text-sm text-muted">{profile.email}</span>
      </Field>
    </Section>
  )
}

const CURRENCIES = ['INR', 'USD', 'EUR', 'GBP', 'AED', 'SGD', 'AUD', 'CAD']
const LOCALES = [
  { value: 'en-IN', label: 'English (India): 1,00,000' },
  { value: 'hi-IN', label: 'हिन्दी (भारत)' },
  { value: 'en-US', label: 'English (US): 100,000' },
  { value: 'en-GB', label: 'English (UK): 100,000' },
]

export function RegionalSettings() {
  const { data: profile } = useProfile()
  const save = useSaveSetting()
  if (!profile) return null
  const { currency, locale, timeZone, budgetStartDay } = profile.settings
  const zones = typeof Intl.supportedValuesOf === 'function' ? Intl.supportedValuesOf('timeZone') : [timeZone]
  const period = budgetPeriod(budgetStartDay)
  const fmt = (d: Date) => d.toLocaleDateString(locale, { day: 'numeric', month: 'short' })
  return (
    <Section title="Region & money" description="India first: rupees, lakh/crore grouping and the April–March financial year. Change anything that doesn't fit you.">
      <Field label="Currency" hint="Your default for new accounts.">
        <Select label="Currency" value={currency} onChange={(v) => save({ currency: v }, 'Currency')}
          options={CURRENCIES.map((c) => ({ value: c, label: `${c} · ${new Intl.DisplayNames([locale], { type: 'currency' }).of(c) ?? c}` }))} />
      </Field>
      <Field label="Number format" hint={`Example: ${formatMoney(12_34_567_89, currency, locale)}`}>
        <Select label="Number format" value={locale} onChange={(v) => save({ locale: v }, 'Number format')} options={LOCALES} />
      </Field>
      <Field label="Time zone">
        <Select label="Time zone" value={timeZone} onChange={(v) => save({ timeZone: v }, 'Time zone')} options={zones.map((z) => ({ value: z, label: z.replace(/_/g, ' ') }))} />
      </Field>
      <Field label="Month starts on" hint={`Set it to your payday so budgets follow your salary. Current period: ${fmt(period.start)} – ${fmt(period.end)}.`}>
        <Select label="Month starts on" value={String(budgetStartDay)} onChange={(v) => save({ budgetStartDay: Number(v) }, 'Budget month')}
          options={Array.from({ length: 28 }, (_, i) => ({ value: String(i + 1), label: i === 0 ? '1st (calendar month)' : `${ordinal(i + 1)} of the month` }))} />
      </Field>
    </Section>
  )
}

export function DashboardSettings() {
  const { prefs, dispatch } = usePreferences()
  const undoable = useUndoable()
  return (
    <Section title="Dashboards" description="Your layouts are saved to your account and look the same on every device.">
      <Field label="Sample data" hint="Show realistic example numbers until your own accounts are connected.">
        <Switch label="Sample data" checked={prefs.sampleData} onCheckedChange={(v) => dispatch({ type: 'sampleData', value: v })} />
      </Field>
      <Field label="Your dashboards" hint="Double-click a tab on the dashboard to rename it.">
        <ul className="flex w-full flex-col gap-1 sm:w-72">
          {prefs.dashboards.map((d) => (
            <li key={d.id} className="flex items-center justify-between gap-2 rounded-control bg-surface-2 px-3 py-2 text-sm">
              <span className="truncate">{d.name}<span className="ml-2 text-xs text-subtle">{d.widgets.length} widgets</span></span>
              {prefs.dashboards.length > 1 && (
                <Button variant="ghost" size="icon-sm" aria-label={`Delete ${d.name}`} onClick={() => undoable({ type: 'removeDashboard', id: d.id }, `Deleted “${d.name}”`)}><Trash2 /></Button>
              )}
            </li>
          ))}
        </ul>
      </Field>
      <Field label="Start over" hint="Resets theme, dashboards and layout to the defaults. You can undo it right after.">
        <Button variant="outline" onClick={() => undoable({ type: 'resetAll', prefs: parsePreferences({}) }, 'Everything reset to defaults')}><RotateCcw />Reset all customisation</Button>
      </Field>
    </Section>
  )
}

export function AccountSettings() {
  const { data: session } = useSession()
  const { data: profile } = useProfile()
  return (
    <Section title="Account" description="Sign-in and security are handled by our identity provider.">
      <Field label="Signed in as"><span className="text-sm text-muted">{profile?.email}</span></Field>
      <Field label="Password & security" hint="Change your password or email, see your active sessions.">
        {session?.accountUrl && <Button variant="outline" asChild><a href={session.accountUrl} target="_blank" rel="noopener noreferrer">Open account security<ExternalLink /></a></Button>}
      </Field>
      <Field label="Your data" hint="Download everything, or delete your account. Coming in Phase 6.">
        <div className="flex gap-2"><Button disabled>Export data</Button><Button disabled variant="danger">Delete account</Button></div>
      </Field>
      <Field label="Sign out" hint="Signs you out on this device and at the identity provider.">
        <Button variant="outline" onClick={() => void signOut()}><LogOut />Sign out</Button>
      </Field>
    </Section>
  )
}

function ordinal(n: number) {
  const s = ['th', 'st', 'nd', 'rd']
  const v = n % 100
  return n + (s[(v - 20) % 10] ?? s[v] ?? s[0]!)
}
