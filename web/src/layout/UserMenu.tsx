import { LogOut, Settings, ShieldCheck } from 'lucide-react'
import { useNavigate } from '@tanstack/react-router'
import { Menu, MenuContent, MenuItem, MenuSeparator, MenuTrigger } from '@/components/ui/overlay'
import { signOut, useSession } from '@/state/session'
import { useProfile } from '@/state/profile'

export function initials(name: string): string {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((p) => p[0]!.toUpperCase()).join('') || '?'
}

export function Avatar({ name, size = 32 }: { name: string; size?: number }) {
  return (
    <span
      className="inline-flex shrink-0 items-center justify-center rounded-full bg-accent text-[0.75rem] font-semibold text-accent-fg"
      style={{ width: size, height: size }}
      aria-hidden
    >
      {initials(name)}
    </span>
  )
}

export function UserMenu() {
  const { data: profile } = useProfile()
  const { data: session } = useSession()
  const navigate = useNavigate()
  if (!profile) return null
  return (
    <Menu>
      <MenuTrigger className="rounded-full focus-visible:ring-2 focus-visible:ring-ring" aria-label="Account menu">
        <Avatar name={profile.displayName} />
      </MenuTrigger>
      <MenuContent>
        <div className="px-2.5 py-2">
          <div className="text-sm font-medium">{profile.displayName}</div>
          <div className="truncate text-xs text-muted">{profile.email}</div>
        </div>
        <MenuSeparator />
        <MenuItem icon={<Settings />} onSelect={() => navigate({ to: '/settings' })}>Settings</MenuItem>
        {session?.accountUrl && (
          <MenuItem icon={<ShieldCheck />} onSelect={() => window.open(session.accountUrl!, '_blank', 'noopener')}>Password & security</MenuItem>
        )}
        <MenuSeparator />
        <MenuItem icon={<LogOut />} onSelect={() => void signOut()}>Sign out</MenuItem>
      </MenuContent>
    </Menu>
  )
}
