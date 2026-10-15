import { useState, type FormEvent } from 'react'
import { useNavigate, useParams } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { ArrowRight, Mail, ShieldCheck, UserRound, Users } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, Skeleton } from '@/components/ui/card'
import { Input } from '@/components/ui/controls'
import { Logo } from '@/layout/Logo'
import { GoogleButton } from '@/components/GoogleButton'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/cn'
import { sessionQuery } from '@/state/session'
import { useGuestLink, useSendGuestCode, useVerifyGuestCode } from '@/state/guest'

/** The router's "come back here after signing in" (router.tsx): the join link opens again once signed in. */
function returnHereAfterSignIn(token: string) {
  try { sessionStorage.setItem('after-sign-in', `/join/${token}`) } catch { /* the link just needs opening again */ }
}

/**
 * A join link opened by someone who isn't signed in. An account is best (their share lands in their own budget), but
 * they can also join as a guest: pick their name, prove their email with a code, and they're in.
 */
export function GuestJoinPage() {
  const { token } = useParams({ from: '/guest/join/$token' })
  const { data: link, isPending, isError } = useGuestLink(token)
  const { data: session } = useQuery(sessionQuery)
  const [asGuest, setAsGuest] = useState(false)

  return (
    <div className="flex min-h-dvh flex-col items-center bg-bg px-4 py-8">
      <Logo />
      <div className="mt-8 w-full max-w-md">
        {isPending ? <Skeleton className="h-72" /> : isError || !link ? (
          <Card className="flex flex-col items-center gap-2 px-pad py-10 text-center">
            <Users className="size-8 text-muted" />
            <p className="font-medium">This invite link doesn't work any more</p>
            <p className="text-sm text-muted">Ask whoever shared it for a new one.</p>
          </Card>
        ) : (
          <Card className="flex flex-col gap-5 p-pad">
            <div className="flex flex-col items-center gap-1 text-center">
              <span className="flex size-16 items-center justify-center rounded-full bg-surface-2 text-3xl" aria-hidden>{link.emoji ?? '👥'}</span>
              <p className="mt-2 text-sm text-muted">You're invited to</p>
              <h1 className="text-2xl font-semibold" data-testid="invite-name">{link.groupName}</h1>
              <p className="text-sm text-muted">{link.members} {link.members === 1 ? 'person' : 'people'} so far</p>
            </div>
            {!asGuest ? (
              <div className="flex flex-col gap-2">
                <Button variant="primary" size="lg" asChild onClick={() => returnHereAfterSignIn(token)}>
                  <a href={session?.signupUrl ?? '/oauth2/authorization/keycloak?signup'}>Create a free account<ArrowRight /></a>
                </Button>
                <p className="text-center text-xs text-muted">With an account, your share also goes into your own budget.</p>
                <Button variant="outline" asChild onClick={() => returnHereAfterSignIn(token)}>
                  <a href={session?.loginUrl ?? '/oauth2/authorization/keycloak'}>I have an account: sign in</a>
                </Button>
                {session?.googleUrl && <GoogleButton href={session.googleUrl} onClick={() => returnHereAfterSignIn(token)} className="w-full" />}
                {link.guests && <>
                  <div className="my-1 flex items-center gap-3 text-xs text-subtle"><span className="h-px flex-1 bg-border" />or<span className="h-px flex-1 bg-border" /></div>
                  <Button variant="ghost" onClick={() => setAsGuest(true)}><UserRound />Continue as a guest</Button>
                </>}
              </div>
            ) : (
              <GuestForm token={token} spots={link.spots} loginUrl={session?.loginUrl ?? '/oauth2/authorization/keycloak'} />
            )}
          </Card>
        )}
      </div>
    </div>
  )
}

function GuestForm({ token, spots, loginUrl }: { token: string; spots: { id: string; name: string }[]; loginUrl: string }) {
  const navigate = useNavigate()
  const send = useSendGuestCode(token)
  const verify = useVerifyGuestCode()
  const [spot, setSpot] = useState<string | 'new'>(spots[0]?.id ?? 'new')
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [sent, setSent] = useState<{ codeId: string; email: string } | null>(null)
  const [code, setCode] = useState('')
  const [error, setError] = useState<{ message: string; signIn?: boolean }>()

  async function sendCode(e: FormEvent) {
    e.preventDefault()
    setError(undefined)
    if (spot === 'new' && !name.trim()) return setError({ message: 'Enter your name.' })
    try {
      setSent(await send.mutateAsync(spot === 'new' ? { name: name.trim(), email } : { memberId: spot, email }))
    } catch (err) {
      setError(err instanceof ApiError ? { message: err.message, signIn: err.code === 'has-account' } : { message: 'Could not send the code. Try again.' })
    }
  }

  async function join(e: FormEvent) {
    e.preventDefault()
    if (!sent) return
    setError(undefined)
    try {
      const joined = await verify.mutateAsync({ codeId: sent.codeId, code })
      void navigate({ to: '/guest/groups/$groupId', params: { groupId: joined.groupId } })
    } catch (err) {
      setError({ message: err instanceof ApiError ? err.message : 'Could not check the code. Try again.' })
    }
  }

  if (sent) {
    return (
      <form onSubmit={join} className="flex flex-col gap-3">
        <p className="flex items-start gap-2 rounded-control bg-surface-2 p-3 text-sm"><Mail className="mt-0.5 size-4 shrink-0 text-accent" />
          <span>We sent a 6-digit code to <b>{sent.email}</b>. It works for 10 minutes.</span></p>
        <label className="flex flex-col gap-1 text-sm font-medium">Code
          <Input value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))} inputMode="numeric" autoComplete="one-time-code"
            placeholder="123456" className="text-center text-xl tracking-[0.5em] tabular" aria-label="Code" autoFocus />
        </label>
        {error && <p className="text-sm text-critical" role="alert">{error.message}</p>}
        <Button variant="primary" type="submit" disabled={code.length !== 6 || verify.isPending}><ShieldCheck />{verify.isPending ? 'Checking…' : 'Join the group'}</Button>
        <button type="button" className="text-xs text-muted underline" onClick={() => { setSent(null); setCode('') }}>Use another email, or send again</button>
      </form>
    )
  }
  return (
    <form onSubmit={sendCode} className="flex flex-col gap-4">
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-2 text-sm font-medium">Which one is you?</legend>
        <div className="flex flex-wrap gap-2" role="radiogroup" aria-label="Which one is you?">
          {spots.map((s) => (
            <button key={s.id} type="button" role="radio" aria-checked={spot === s.id} onClick={() => setSpot(s.id)}
              className={cn('rounded-full border px-3 py-1.5 text-sm', spot === s.id ? 'border-accent bg-accent-soft font-medium' : 'border-border hover:border-border-strong')}>{s.name}</button>
          ))}
          <button type="button" role="radio" aria-checked={spot === 'new'} onClick={() => setSpot('new')}
            className={cn('rounded-full border border-dashed px-3 py-1.5 text-sm', spot === 'new' ? 'border-accent bg-accent-soft font-medium' : 'border-border-strong')}>
            {spots.length ? 'Not listed' : 'Add my name'}</button>
        </div>
        {spot === 'new' && <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="Your name" aria-label="Your name" maxLength={60} />}
      </fieldset>
      <label className="flex flex-col gap-1 text-sm font-medium">Your email
        <Input type="email" required value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@example.com" autoComplete="email" aria-label="Your email" />
        <span className="text-xs font-normal text-muted">We send a code to prove it's you. No password, no account.</span>
      </label>
      {error && (
        <p className="text-sm text-critical" role="alert">{error.message}
          {error.signIn && <> <a className="font-medium underline" href={loginUrl} onClick={() => returnHereAfterSignIn(token)}>Sign in</a></>}</p>
      )}
      <Button variant="primary" type="submit" disabled={send.isPending || !email}><Mail />{send.isPending ? 'Sending…' : 'Email me a code'}</Button>
    </form>
  )
}
