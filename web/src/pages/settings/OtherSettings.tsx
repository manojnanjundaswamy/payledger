import { useState, type FormEvent } from 'react'
import { CurrencyChangeDialog } from '@/components/CurrencyChangeDialog'
import { SearchSelect } from '@/components/ui/search-select'
import { allCurrencies, currencySymbol } from '@/lib/money'
import { LOCALES, MONTHS, REGIONS, regionOf } from '@/lib/regions'
import { Check, Download, ExternalLink, FileJson, LogOut, RotateCcw, Trash2, Upload } from 'lucide-react'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input, Switch } from '@/components/ui/controls'
import { Dialog } from '@/components/ui/overlay'
import { ApiError, api } from '@/lib/api'
import { budgetPeriod } from '@/dashboard/sample'
import { formatMoney } from '@/lib/money'
import { parsePreferences } from '@/state/preferences'
import { usePreferences, useUndoable } from '@/state/PreferencesProvider'
import { useProfile, useUpdateProfile, type ProfileUpdate } from '@/state/profile'
import { signOut, useSession } from '@/state/session'
import { UPI_ID } from '@/lib/upi'
import { useAccounts, useGroupBudget, useSetGroupBudget, usePersonalSpaceId } from '@/state/money'
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
      {/* UPI is India's: asked of people there, or who already set one. */}
      {(profile.settings.country === 'IN' || !profile.settings.country || profile.settings.upiId) && <Field label="UPI ID" htmlFor="upi-id" hint="Friends who owe you in a group get a one-tap Pay button. Only people you share a group with see it.">
        <form className="flex w-full flex-col gap-1 sm:w-auto" onSubmit={(e) => { e.preventDefault(); if (upiOk) save({ upiId: upi.trim() }, 'UPI ID') }}>
          <span className="flex gap-2">
            <Input id="upi-id" value={upi} maxLength={100} placeholder="name@okhdfcbank" onChange={(e) => setUpi(e.target.value)} className="sm:w-56" aria-invalid={!upiOk} />
            <Button type="submit" disabled={!upiOk || upi.trim() === (profile.settings.upiId ?? '')}>Save</Button>
          </span>
          {!upiOk && <span className="text-xs text-critical">That doesn't look like a UPI ID (name@bank).</span>}
        </form>
      </Field>}
      <Field label="Email" hint="Your sign-in email. Change it under Account.">
        <span className="text-sm text-muted">{profile.email}</span>
      </Field>
    </Section>
  )
}


export function RegionalSettings() {
  const { data: profile } = useProfile()
  const save = useSaveSetting()
  const [switchTo, setSwitchTo] = useState<string | null>(null)
  if (!profile) return null
  const { locale, timeZone, budgetStartDay } = profile.settings
  const personal = profile.spaces.find((s) => s.kind === 'PERSONAL')
  const spaceCurrency = personal?.currency ?? profile.settings.currency
  const zones = typeof Intl.supportedValuesOf === 'function' ? Intl.supportedValuesOf('timeZone') : [timeZone]
  const period = budgetPeriod(budgetStartDay)
  const fmt = (d: Date) => d.toLocaleDateString(locale, { day: 'numeric', month: 'short' })
  return (
    <>
    <Section title="Region & money" description="Where you live sets the currency, number format, time zone and financial year. Change anything that doesn't fit you.">
      <Field label="Where you live" hint="Choosing a place fills in its usual currency, format, time zone and financial year.">
        <Select label="Where you live" value={profile.settings.country ?? ''} onChange={(v) => {
          const r = regionOf(v)
          save(r ? { country: v, locale: r.locale, timeZone: r.timeZone, fyStartMonth: r.fyStartMonth } : { country: v }, 'Region')
          // The currency is the money's own: changing it is a choice (convert or keep the numbers), so it's offered.
          if (r && r.currency !== spaceCurrency) setSwitchTo(r.currency)
        }} options={[...(profile.settings.country ? [] : [{ value: '', label: 'Not set yet' }]), ...REGIONS.map((r) => ({ value: r.code, label: `${r.flag} ${r.name}` }))]} />
      </Field>
      <Field label="Currency" hint="Your money's currency. Changing it asks whether to convert at an exchange rate or keep the numbers.">
        <SearchSelect id="settings-currency" aria-label="Currency" value={spaceCurrency} onChange={(v) => v !== spaceCurrency && setSwitchTo(v)}
          options={allCurrencies(locale).map((c) => ({ value: c.code, label: `${c.code} · ${c.name}`, icon: <span className="w-6 text-center text-xs font-semibold" aria-hidden>{currencySymbol(c.code, locale)}</span> }))} />
      </Field>
      {personal && switchTo && (
        <CurrencyChangeDialog spaceId={personal.id} to={switchTo} open={!!switchTo} onOpenChange={(o) => !o && setSwitchTo(null)} />
      )}
      <Field label="Financial year starts" hint="For yearly reports. India, the UK and Japan: April; Australia: July; most places: January.">
        <Select label="Financial year starts" value={String(profile.settings.fyStartMonth ?? 4)} onChange={(v) => save({ fyStartMonth: Number(v) }, 'Financial year')}
          options={MONTHS.map((m, i) => ({ value: String(i + 1), label: m }))} />
      </Field>
      <Field label="Number format" hint={`Example: ${formatMoney(12_34_567_89, spaceCurrency, locale)}`}>
        <Select label="Number format" value={locale} onChange={(v) => save({ locale: v }, 'Number format')}
          options={LOCALES.some((l) => l.value === locale) ? LOCALES : [{ value: locale, label: locale }, ...LOCALES]} />
      </Field>
      <Field label="Time zone">
        <Select label="Time zone" value={timeZone} onChange={(v) => save({ timeZone: v }, 'Time zone')} options={zones.map((z) => ({ value: z, label: z.replace(/_/g, ' ') }))} />
      </Field>
      <Field label="Month starts on" hint={`Set it to your payday so budgets follow your salary. Current period: ${fmt(period.start)} – ${fmt(period.end)}.`}>
        <Select label="Month starts on" value={String(budgetStartDay)} onChange={(v) => save({ budgetStartDay: Number(v) }, 'Budget month')}
          options={Array.from({ length: 28 }, (_, i) => ({ value: String(i + 1), label: i === 0 ? '1st (calendar month)' : `${ordinal(i + 1)} of the month` }))} />
      </Field>
    </Section>
    <GroupBudgetSettings />
    </>
  )
}

/** Whether your share of group expenses counts in your budget, and the account you usually pay friends from. */
function GroupBudgetSettings() {
  const { data } = useGroupBudget()
  const { data: accounts } = useAccounts(false, usePersonalSpaceId())
  const set = useSetGroupBudget()
  if (!data) return null
  const save = (enabled: boolean, accountId: string | null, what: string) =>
    set.mutate({ enabled, accountId }, {
      onSuccess: () => toast.success(`${what} saved`),
      onError: (e) => toast.error(`Couldn't save ${what.toLowerCase()}`, { description: e.message }),
    })
  const payable = (accounts ?? []).filter((a) => a.kind !== 'OWED' && !a.archived)
  return (
    <Section title="Groups in your budget"
      description="Your share of a group expense counts as your spending, in your category of the same name. What you paid for others isn't spending: it waits in Friends & groups until they pay you back.">
      <Field label="Count my share in my budget" hint="Turning it on adds what's already in your groups.">
        <Switch checked={data.enabled} onCheckedChange={(on) => save(on, data.accountId, 'Groups in your budget')} label="Count my share in my budget" />
      </Field>
      {data.enabled && (
        <Field label="I usually pay from" hint="You can pick another account on each expense or payment.">
          <Select label="I usually pay from" value={data.accountId ?? ''} onChange={(v) => save(true, v || null, 'Account')}
            options={[{ value: '', label: 'My first bank account' }, ...payable.map((a) => ({ value: a.id, label: a.name }))]} />
        </Field>
      )}
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
      <Field label="Streaks and badges" hint="A streak for recording your money, badges for good habits, and a small challenge each week.">
        <Switch label="Streaks and badges" checked={prefs.gamification} onCheckedChange={(v) => dispatch({ type: 'gamification', on: v })} />
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
  const [deleting, setDeleting] = useState(false)
  const [restoring, setRestoring] = useState(false)
  return (
    <Section title="Account" description="Sign-in and security are handled by our identity provider.">
      <Field label="Signed in as"><span className="text-sm text-muted">{profile?.email}</span></Field>
      <Field label="Password & security" hint="Change your password or email, see your active sessions.">
        {session?.accountUrl && <Button variant="outline" asChild><a href={session.accountUrl} target="_blank" rel="noopener noreferrer">Open account security<ExternalLink /></a></Button>}
      </Field>
      <Field label="Your data" hint="Everything we keep about you, as one file: accounts, transactions, budgets, bills, goals, groups and notifications.">
        <Button variant="outline" asChild><a href="/api/core/me/export" download><Download />Download all my data</a></Button>
      </Field>
      <Field label="Restore from a download" hint="Bring a PayLedger download back into this account: after starting over, or moving to a new email.">
        <Button variant="outline" onClick={() => setRestoring(true)}><Upload />Restore my data</Button>
      </Field>
      <RestoreDialog open={restoring} onOpenChange={setRestoring} />
      <Field label="Delete account" hint="Your data and your sign-in go for good. Groups you share keep their history, with you as a name only.">
        <Button variant="danger" onClick={() => setDeleting(true)}><Trash2 />Delete my account</Button>
      </Field>
      <DeleteAccountDialog open={deleting} onOpenChange={setDeleting} email={profile?.email ?? ''} />
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

/** Deleting is final, so the email is typed back first; then the session ends. */
function DeleteAccountDialog({ open, onOpenChange, email }: { open: boolean; onOpenChange: (o: boolean) => void; email: string }) {
  const [typed, setTyped] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(undefined)
    setBusy(true)
    try {
      await api<void>('/api/core/me/delete', { method: 'POST', json: { confirm: typed } })
      await signOut()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not delete it. Try again.')
      setBusy(false)
    }
  }
  return (
    <Dialog open={open} onOpenChange={(o) => { onOpenChange(o); if (!o) { setTyped(''); setError(undefined) } }} title="Delete your account?"
      description="This can't be undone. Download your data first if you want a copy.">
      <form onSubmit={submit} className="flex flex-col gap-4 p-pad" noValidate>
        <ul className="list-disc pl-5 text-sm text-muted">
          <li>Your accounts, transactions, budgets, bills, goals and settings are deleted.</li>
          <li>In groups you share, the history stays for the others, with you as a name only.</li>
          <li>You're signed out, and this email can sign up again as a new account.</li>
        </ul>
        <label className="flex flex-col gap-1.5 text-sm font-medium">Type your email to confirm
          <Input value={typed} onChange={(e) => setTyped(e.target.value)} placeholder={email} autoComplete="off" />
        </label>
        {error && <p className="rounded-control bg-critical/10 px-3 py-2 text-sm text-critical" role="alert">{error}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button type="submit" variant="danger" disabled={busy || typed.trim().toLowerCase() !== email.toLowerCase()}>Delete for good</Button>
        </div>
      </form>
    </Dialog>
  )
}

interface Restored { counts: Record<string, number>; skipped: string[] }

const RESTORED_LABELS: Record<string, string> = {
  categories: 'categories', accounts: 'accounts', goals: 'goals', transactions: 'entries', budgets: 'budgets', billsAndSavings: 'bills and savings',
  billPayments: 'bill payments', goalContributions: 'goal top-ups', categoryRules: 'category rules', taxMappings: 'tax sections',
  dailyServices: 'daily services', dailyServiceDays: 'marked days', dailyServicePayments: 'service payments',
}

/**
 * A PayLedger download, brought back: settings, accounts, entries, budgets, bills, goals, rules. Only into an account
 * with no money in it yet, so nothing is doubled. Groups and households stay with the people you share them with.
 */
function RestoreDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const [file, setFile] = useState<File | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  const [done, setDone] = useState<Restored | null>(null)
  const client = useQueryClient()
  async function restore() {
    if (!file) return
    setBusy(true)
    setError(undefined)
    try {
      const form = new FormData()
      form.append('file', file)
      const r = await api<Restored>('/api/core/me/import', { method: 'POST', body: form })
      setDone(r)
      void client.invalidateQueries()
      toast.success('Your data is back')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not restore it. Try again.')
    } finally {
      setBusy(false)
    }
  }
  const close = (o: boolean) => { if (!o) { setFile(null); setDone(null); setError(undefined) } onOpenChange(o) }
  return (
    <Dialog open={open} onOpenChange={close} title="Restore your data"
      description="From a file you downloaded here (Download all my data). It goes into this account, which must have no accounts or entries yet.">
      <div className="flex flex-col gap-4 p-pad">
        {!done ? (
          <>
            <label className="flex cursor-pointer flex-col items-center gap-2 rounded-card border-2 border-dashed border-border-strong p-6 text-center hover:border-accent">
              <FileJson className="size-8 text-accent" />
              <span className="text-sm">{file ? <b>{file.name}</b> : <><b>Choose the download</b> (payledger-my-data-….json)</>}</span>
              <input type="file" accept=".json,application/json" className="sr-only" aria-label="Your PayLedger download" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
            </label>
            <ul className="list-disc space-y-1 pl-5 text-xs text-muted">
              <li>Comes back: your settings, accounts and balances, every entry, budgets, bills, goals, category rules and tax sections.</li>
              <li>Stays with others: groups and households, because they're shared. Ask to be added again.</li>
            </ul>
            {error && <p className="rounded-control bg-critical/10 px-3 py-2 text-sm text-critical" role="alert">{error}</p>}
            <div className="flex justify-end gap-2">
              <Button variant="ghost" onClick={() => close(false)}>Cancel</Button>
              <Button variant="primary" disabled={!file || busy} onClick={() => void restore()}><Upload />{busy ? 'Restoring…' : 'Restore'}</Button>
            </div>
          </>
        ) : (
          <div className="flex flex-col gap-3" data-testid="restored">
            <p className="flex items-center gap-2 font-medium text-good-text"><Check className="size-5" />Restored</p>
            <ul className="grid grid-cols-2 gap-1 text-sm">
              {Object.entries(done.counts).filter(([k, v]) => k !== 'settings' && v > 0).map(([k, v]) => (
                <li key={k}><b className="tabular">{v}</b> {RESTORED_LABELS[k] ?? k}</li>
              ))}
            </ul>
            {done.skipped.length > 0 && (
              <ul className="list-disc space-y-1 pl-5 text-xs text-muted">{done.skipped.map((x) => <li key={x}>{x}</li>)}</ul>
            )}
            <Button variant="primary" className="self-end" onClick={() => close(false)}>Done</Button>
          </div>
        )}
      </div>
    </Dialog>
  )
}
