import { useEffect, useState, type FormEvent } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card, Skeleton } from '@/components/ui/card'
import { Input, Segmented, Switch } from '@/components/ui/controls'
import { ApiError } from '@/lib/api'
import { Field } from '@/pages/money/parts'
import { useAppSettings, useSaveAppSettings, type AppSettings } from '@/state/admin'

const LIMITS: { key: keyof AppSettings['freeLimits']; label: string }[] = [
  { key: 'accounts', label: 'Open accounts' },
  { key: 'budgets', label: 'Budgets' },
  { key: 'bills', label: 'Bills and savings' },
  { key: 'bigCosts', label: 'Big yearly costs' },
  { key: 'imports', label: 'Imports a month' },
]

/** Free's limits, the trial length, and a message for everyone. */
export function AdminSettings() {
  const { data, isPending, isError } = useAppSettings()
  const save = useSaveAppSettings()
  const [draft, setDraft] = useState<AppSettings | null>(null)
  const [announce, setAnnounce] = useState(false)
  useEffect(() => {
    if (data) {
      setDraft(data)
      setAnnounce(!!data.announcement)
    }
  }, [data])

  if (isError) return <Card className="p-pad text-sm text-critical">Couldn't load settings. Refresh to try again.</Card>
  if (isPending || !draft) return <Skeleton className="h-64" />

  const a = draft.announcement ?? { message: '', tone: 'INFO' as const, until: null }
  async function submit(e: FormEvent) {
    e.preventDefault()
    if (announce && !a.message.trim()) return toast.error('Write the announcement, or turn it off.')
    try {
      await save.mutateAsync({ ...draft!, announcement: announce ? { ...a, message: a.message.trim(), until: a.until || null } : null })
      toast.success('Settings saved', { description: 'They apply from the next add; nothing anyone has is touched.' })
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Could not save. Try again.')
    }
  }
  const num = (v: string) => Math.max(0, Math.min(1000, Number.parseInt(v || '0', 10) || 0))

  return (
    <form onSubmit={submit} className="flex max-w-3xl flex-col gap-6">
      <Card className="flex flex-col gap-4 p-pad">
        <div>
          <h3 className="font-medium">Free plan limits</h3>
          <p className="text-sm text-muted">Limits only stop adding. Nobody loses what they already have; viewing and exporting are never limited.</p>
        </div>
        <div className="grid gap-3 sm:grid-cols-3 lg:grid-cols-5">
          {LIMITS.map((l) => (
            <Field key={l.key} label={l.label} htmlFor={`limit-${l.key}`}>
              <Input id={`limit-${l.key}`} inputMode="numeric" className="tabular" value={draft.freeLimits[l.key]}
                onChange={(e) => setDraft({ ...draft, freeLimits: { ...draft.freeLimits, [l.key]: num(e.target.value) } })} />
            </Field>
          ))}
        </div>
        <p className="text-xs text-subtle">The landing page's pricing table still shows the standard limits; update it when these change for good.</p>
      </Card>

      <Card className="flex flex-col gap-4 p-pad">
        <h3 className="font-medium">Premium trial</h3>
        <Field label="Trial length in days" htmlFor="trial-days" hint="0 stops offering the trial. Trials already running keep their end date.">
          <Input id="trial-days" inputMode="numeric" className="w-32 tabular" value={draft.trialDays}
            onChange={(e) => setDraft({ ...draft, trialDays: Math.min(90, num(e.target.value)) })} />
        </Field>
      </Card>

      <Card className="flex flex-col gap-4 p-pad">
        <div className="flex items-center justify-between gap-3">
          <div>
            <h3 className="font-medium">Announcement</h3>
            <p className="text-sm text-muted">A short message at the top of the app for everyone signed in.</p>
          </div>
          <Switch checked={announce} onCheckedChange={setAnnounce} label="Show an announcement" />
        </div>
        {announce && (
          <>
            <Field label="Message" htmlFor="announce-message">
              <Input id="announce-message" maxLength={280} placeholder="Maintenance on Sunday 2–3 am IST; the app may be slow."
                value={a.message} onChange={(e) => setDraft({ ...draft, announcement: { ...a, message: e.target.value } })} />
            </Field>
            <div className="flex flex-wrap items-end gap-4">
              <Field label="Tone" htmlFor="announce-tone">
                <Segmented label="Tone" value={a.tone} onChange={(tone) => setDraft({ ...draft, announcement: { ...a, tone } })}
                  options={[{ value: 'INFO', label: 'Info' }, { value: 'WARNING', label: 'Warning' }]} />
              </Field>
              <Field label="Last day (optional)" htmlFor="announce-until">
                <Input id="announce-until" type="date" value={a.until ?? ''}
                  onChange={(e) => setDraft({ ...draft, announcement: { ...a, until: e.target.value || null } })} />
              </Field>
            </div>
          </>
        )}
      </Card>

      <div className="flex justify-end gap-2">
        <Button variant="ghost" onClick={() => { setDraft(data!); setAnnounce(!!data!.announcement) }}>Reset</Button>
        <Button type="submit" variant="primary" disabled={save.isPending}>Save settings</Button>
      </div>
    </form>
  )
}
