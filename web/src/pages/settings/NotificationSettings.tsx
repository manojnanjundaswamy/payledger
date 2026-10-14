import { CheckCircle2, Clock, Mail, XCircle } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Switch } from '@/components/ui/controls'
import { ApiError } from '@/lib/api'
import { useEmailPrefs, useSentEmails, useSetEmailPrefs, useTestEmail, type EmailKind } from '@/state/notifications'
import { useProfile } from '@/state/profile'
import { Field, Section } from './parts'

const KINDS: { kind: EmailKind; label: string; hint: string }[] = [
  { kind: 'BILL_REMINDERS', label: 'Bills due soon', hint: 'A morning email when bills or savings on your plan are due in the next 3 days.' },
  { kind: 'BUDGET_ALERTS', label: 'Budget alerts', hint: 'When a budget gets close to its limit (80%) and when it goes over. Once each a month.' },
  { kind: 'GROUP_ACTIVITY', label: 'Groups', hint: 'When someone adds you to a group.' },
  { kind: 'WEEKLY_RECAP', label: 'Weekly recap', hint: 'Monday morning: last week\u2019s spending against the week before, where it went, and what\u2019s due this week.' },
  { kind: 'PRODUCT_NEWS', label: 'Product news', hint: 'Now and then, what\u2019s new in PayLedger. Off unless you turn it on.' },
]

/** Which emails you get, a test email, and what was sent lately. */
export function NotificationSettings() {
  const { data: prefs } = useEmailPrefs()
  const set = useSetEmailPrefs()
  const test = useTestEmail()
  const { data: sent = [] } = useSentEmails()
  const email = useProfile().data?.email
  const locale = useProfile().data?.settings.locale ?? 'en-IN'

  async function sendTest() {
    try {
      await test.mutateAsync()
      toast.success('Test email on its way', { description: `To ${email}. It should arrive in a minute.` })
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Could not send. Try again.')
    }
  }

  return (
    <div className="flex max-w-2xl flex-col gap-8">
      <Section title="Email" description={`Sent to ${email ?? 'your email'}. Account emails (sign-in, verification) always go.`}>
        {KINDS.map((k) => (
          <Field key={k.kind} label={k.label} hint={k.hint}>
            <Switch label={k.label} checked={prefs?.[k.kind] ?? false}
              onCheckedChange={(on) => set.mutate({ [k.kind]: on }, { onSuccess: () => toast.success(`${k.label}: ${on ? 'on' : 'off'}`) })} />
          </Field>
        ))}
        <Field label="Check it reaches you" hint="Sends one email to you now.">
          <Button variant="outline" onClick={sendTest} disabled={test.isPending}><Mail />Send a test email</Button>
        </Field>
      </Section>

      <Section title="Recent emails" description="What PayLedger sent you lately, and whether it went.">
        {sent.length === 0 ? <p className="p-pad text-sm text-muted">Nothing yet.</p> : (
          <ul className="divide-y divide-border" data-testid="sent-emails">
            {sent.map((e, i) => (
              <li key={i} className="flex items-center gap-3 p-pad text-sm">
                {e.sent_at ? <CheckCircle2 className="size-4 shrink-0 text-good-text" aria-label="Sent" />
                  : e.attempts >= 6 ? <XCircle className="size-4 shrink-0 text-critical" aria-label="Failed" />
                  : <Clock className="size-4 shrink-0 text-muted" aria-label="Waiting" />}
                <span className="min-w-0 flex-1">
                  <span className="block truncate">{e.subject}</span>
                  <span className="block text-xs text-subtle">
                    {new Date(e.created_at).toLocaleString(locale, { dateStyle: 'medium', timeStyle: 'short' })}
                    {!e.sent_at && e.last_error && ` \u00b7 retrying: ${e.last_error}`}
                  </span>
                </span>
              </li>
            ))}
          </ul>
        )}
      </Section>
    </div>
  )
}
