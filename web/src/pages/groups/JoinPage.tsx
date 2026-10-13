import { useNavigate, useParams } from '@tanstack/react-router'
import { Users } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, Skeleton } from '@/components/ui/card'
import { useInvite, useJoin } from '@/state/groups'

/** Where an invite link lands, after sign-in: what the group is, and a button to join it. */
export function JoinPage() {
  const { token } = useParams({ from: '/app/join/$token' })
  const { data: invite, isPending, isError } = useInvite(token)
  const join = useJoin(token)
  const navigate = useNavigate()

  async function joinIt() {
    const group = await join.mutateAsync(undefined)
    void navigate({ to: '/groups/$groupId', params: { groupId: group!.id } })
  }

  if (isPending) return <div className="mx-auto max-w-md"><Skeleton className="h-48" /></div>
  if (isError || !invite) {
    return (
      <Card className="mx-auto flex max-w-md flex-col items-center gap-2 px-pad py-10 text-center">
        <Users className="size-8 text-muted" />
        <p className="font-medium">This invite link doesn't work any more</p>
        <p className="text-sm text-muted">Ask whoever shared it for a new one.</p>
      </Card>
    )
  }
  return (
    <Card className="mx-auto flex max-w-md flex-col items-center gap-3 px-pad py-10 text-center">
      <span className="flex size-16 items-center justify-center rounded-full bg-surface-2 text-3xl" aria-hidden>{invite.emoji ?? '👥'}</span>
      <p className="text-sm text-muted">You're invited to</p>
      <h2 className="text-2xl font-semibold" data-testid="invite-name">{invite.groupName}</h2>
      <p className="text-sm text-muted">{invite.members} {invite.members === 1 ? 'person' : 'people'} so far</p>
      <Button variant="primary" onClick={joinIt} disabled={join.isPending}>{invite.alreadyMember ? 'Open the group' : 'Join the group'}</Button>
    </Card>
  )
}
