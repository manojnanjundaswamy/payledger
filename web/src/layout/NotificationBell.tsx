import { useEffect, useRef, useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { useQueryClient } from '@tanstack/react-query'
import { AlarmClock, Bell, CalendarClock, CalendarRange, CheckCheck, HandCoins, KeyRound, PieChart, Receipt, UserPlus, type LucideIcon } from 'lucide-react'
import { Popover } from 'radix-ui'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { api } from '@/lib/api'
import { cn } from '@/lib/cn'
import { useInbox, useMarkRead, useUnreadCount, type InboxItem, type InboxKind } from '@/state/inbox'
import { useProfile } from '@/state/profile'

const ICONS: Record<InboxKind, LucideIcon> = {
  GROUP_EXPENSE: Receipt, SETTLEMENT: HandCoins, GROUP_MEMBER: UserPlus, BILL_DUE: CalendarClock,
  BILL_OVERDUE: AlarmClock, BUDGET: PieChart, SIGN_IN: KeyRound, RECAP: CalendarRange, LOAN_DUE: HandCoins,
}

/** The bell: how many are unread, and a panel with the latest. New ones also pop up while you're here. */
export function NotificationBell() {
  const [open, setOpen] = useState(false)
  const { data: count } = useUnreadCount()
  const { data, isPending } = useInbox(open)
  const markRead = useMarkRead()
  const navigate = useNavigate()
  const client = useQueryClient()
  const locale = useProfile().data?.settings.locale ?? 'en-IN'
  const unread = count?.unread ?? 0

  // Something new arrived (someone in your group added an expense): show it right away.
  const last = useRef<number | null>(null)
  useEffect(() => {
    if (count === undefined) return
    const before = last.current
    last.current = count.unread
    if (before === null || count.unread <= before) return
    void api<{ items: InboxItem[] }>('/api/core/me/inbox?limit=' + Math.min(3, count.unread - before)).then((page) => {
      void client.invalidateQueries({ queryKey: ['inbox', 'list'] })
      for (const item of page.items.filter((i) => !i.readAt).slice(0, 3)) {
        toast(item.title, {
          description: item.body ?? undefined,
          action: item.link ? { label: 'Open', onClick: () => go(item) } : undefined,
        })
      }
    }).catch(() => { /* the badge still shows it */ })
  }, [count?.unread])

  function go(item: InboxItem) {
    if (!item.readAt) markRead.mutate([item.id])
    setOpen(false)
    if (item.link) void navigate({ href: item.link })
  }

  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger asChild>
        <Button variant="ghost" size="icon" aria-label={unread ? `Notifications, ${unread} unread` : 'Notifications'} className="relative">
          <Bell />
          {unread > 0 && (
            <span className="absolute -top-0.5 -right-0.5 flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-critical px-1 text-[0.625rem] font-semibold text-white tabular"
              data-testid="unread-count">{unread > 99 ? '99+' : unread}</span>
          )}
        </Button>
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content align="end" sideOffset={8} aria-label="Notifications"
          className="z-50 flex max-h-[min(70dvh,560px)] w-[min(94vw,400px)] flex-col overflow-hidden rounded-card border border-border bg-surface shadow-lift data-[state=open]:animate-[pop-in_140ms_cubic-bezier(.2,.8,.2,1)]">
          <div className="flex items-center justify-between gap-2 border-b border-border px-4 py-3">
            <h2 className="text-sm font-semibold">Notifications</h2>
            {unread > 0 && (
              <Button size="sm" variant="ghost" onClick={() => markRead.mutate(undefined)} disabled={markRead.isPending}><CheckCheck />Mark all read</Button>
            )}
          </div>
          <div className="overflow-y-auto">
            {isPending ? <p className="p-4 text-sm text-muted">Loading…</p>
              : !data || data.items.length === 0 ? (
                <p className="p-6 text-center text-sm text-muted">Nothing yet. Expenses in your groups, bills due and budget alerts show up here.</p>
              ) : (
                <ul className="divide-y divide-border" data-testid="inbox-list">
                  {data.items.map((item) => {
                    const Icon = ICONS[item.kind] ?? Bell
                    return (
                      <li key={item.id}>
                        <button type="button" onClick={() => go(item)}
                          className={cn('flex w-full items-start gap-3 px-4 py-3 text-left hover:bg-surface-2', !item.readAt && 'bg-accent-soft/40')}>
                          <span className="mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-full bg-surface-2 text-muted"><Icon className="size-4" /></span>
                          <span className="min-w-0 flex-1">
                            <span className={cn('block text-sm', !item.readAt && 'font-medium')}>{item.title}</span>
                            {item.body && <span className="block text-xs text-muted">{item.body}</span>}
                            <span className="block text-xs text-subtle">{ago(item.createdAt, locale)}</span>
                          </span>
                          {!item.readAt && <span className="mt-2 size-2 shrink-0 rounded-full bg-accent" aria-label="Unread" />}
                        </button>
                      </li>
                    )
                  })}
                </ul>
              )}
          </div>
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  )
}

function ago(iso: string, locale: string): string {
  const minutes = Math.round((Date.now() - new Date(iso).getTime()) / 60_000)
  if (minutes < 1) return 'just now'
  if (minutes < 60) return `${minutes} min ago`
  if (minutes < 24 * 60) return `${Math.round(minutes / 60)} h ago`
  return new Date(iso).toLocaleDateString(locale, { day: 'numeric', month: 'short' })
}
