import { useState, type FormEvent } from 'react'
import { Lock, UserPlus } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card, Skeleton } from '@/components/ui/card'
import { Input } from '@/components/ui/controls'
import { ApiError } from '@/lib/api'
import { Field, Select } from '@/pages/money/parts'
import { useStaff, useStaffChange, type StaffRoleName } from '@/state/admin'
import { useProfile } from '@/state/profile'

export const ROLES: { value: StaffRoleName; label: string; does: string }[] = [
  { value: 'OWNER', label: 'Owner', does: 'Everything, including who is staff.' },
  { value: 'ADMIN', label: 'Admin', does: 'Users, plans, settings and the audit log. Not staff.' },
  { value: 'SUPPORT', label: 'Support', does: 'Find users, resend emails, sign them out. No plan or settings changes.' },
  { value: 'ANALYST', label: 'Analyst', does: 'The dashboard numbers only. Never a person.' },
]

/** Who is staff and with which role. */
export function AdminStaff() {
  const { data, isPending, isError } = useStaff()
  const change = useStaffChange()
  const me = useProfile().data?.id
  const [email, setEmail] = useState('')
  const [role, setRole] = useState<StaffRoleName>('SUPPORT')

  async function apply(c: Parameters<typeof change.mutateAsync>[0], done: string) {
    try {
      await change.mutateAsync(c)
      toast.success(done)
      return true
    } catch (err) {
      toast.error(err instanceof ApiError && err.status === 404 ? 'No user with that email. They need to sign up first.'
        : err instanceof ApiError ? err.message : 'That didn’t work. Try again.')
      return false
    }
  }

  async function add(e: FormEvent) {
    e.preventDefault()
    if (!email.trim()) return
    if (await apply({ grant: { email: email.trim(), role } }, `${email.trim()} is now ${role.toLowerCase()}`)) setEmail('')
  }

  if (isError) return <Card className="p-pad text-sm text-critical">Couldn't load staff. Refresh to try again.</Card>
  if (isPending || !data) return <Skeleton className="h-64" />

  return (
    <div className="grid gap-6 lg:grid-cols-[1fr_20rem]">
      <Card className="divide-y divide-border self-start p-0" data-testid="staff-list">
        {data.map((m) => (
          <div key={m.userId} className="flex flex-wrap items-center gap-3 px-pad py-3 text-sm">
            <span className="min-w-0 flex-1 basis-56">
              <span className="block truncate font-medium">{m.displayName}{m.userId === me && ' (you)'}</span>
              <span className="block truncate text-xs text-muted">{m.email}</span>
            </span>
            {m.configured || m.userId === me ? (
              <span className="flex items-center gap-1.5 text-xs text-muted" title={m.configured ? 'Set in ADMIN_EMAILS' : 'You can’t change your own role'}>
                <Lock className="size-3.5" />{ROLES.find((r) => r.value === m.role)?.label}{m.configured && ' · ADMIN_EMAILS'}
              </span>
            ) : (
              <>
                <Select aria-label={`Role of ${m.email}`} value={m.role} className="h-8 w-32"
                  onChange={(e) => void apply({ grant: { email: m.email, role: e.target.value as StaffRoleName } }, 'Role changed')}>
                  {ROLES.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}
                </Select>
                <Button size="sm" variant="ghost" onClick={() => void apply({ remove: m.userId }, `${m.email} is no longer staff`)}>Remove</Button>
              </>
            )}
          </div>
        ))}
      </Card>

      <div className="flex flex-col gap-4">
        <Card className="p-pad">
          <form onSubmit={add} className="flex flex-col gap-3">
            <h3 className="font-medium">Add staff</h3>
            <Field label="Their email" htmlFor="staff-email" hint="They must have a PayLedger account.">
              <Input id="staff-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
            </Field>
            <Field label="Role" htmlFor="staff-role">
              <Select id="staff-role" value={role} onChange={(e) => setRole(e.target.value as StaffRoleName)}>
                {ROLES.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}
              </Select>
            </Field>
            <Button type="submit" variant="primary" disabled={!email.trim() || change.isPending}><UserPlus />Add</Button>
          </form>
        </Card>
        <Card className="flex flex-col gap-2 p-pad text-sm">
          <h3 className="font-medium">What each role can do</h3>
          {ROLES.map((r) => <p key={r.value}><span className="font-medium">{r.label}:</span> <span className="text-muted">{r.does}</span></p>)}
        </Card>
      </div>
    </div>
  )
}
