import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useCaptureCount } from '@/state/capture'
import { Link, Outlet, useRouterState } from '@tanstack/react-router'
import { motion, MotionConfig } from 'motion/react'
import { Check, CloudOff, Loader2, MoreHorizontal, PanelLeftClose, PanelLeftOpen, Plus, Search } from 'lucide-react'
import { Tooltip as TooltipPrimitive } from 'radix-ui'
import { Button } from '@/components/ui/button'
import { Kbd } from '@/components/ui/card'
import { Tooltip } from '@/components/ui/controls'
import { Dialog } from '@/components/ui/overlay'
import { cn } from '@/lib/cn'
import { usePreferences } from '@/state/PreferencesProvider'
import { useMediaQuery } from '@/hooks/useMediaQuery'
import { AddMenu } from './AddMenu'
import { EdgeDock } from '@/utilities/EdgeDock'
import { UtilityWindowsProvider } from '@/utilities/windows'
import { AnnouncementBanner } from './AnnouncementBanner'
import { NotificationBell } from './NotificationBell'
import { SpaceSwitcher } from './SpaceSwitcher'
import { CommandPalette, useCommandPaletteShortcut } from './CommandPalette'
import { Logo } from './Logo'
import { useNav, type NavItem } from './nav'
import { ThemeQuickToggle } from './ThemeQuickToggle'
import { WidthToggle } from './WidthToggle'
import { UserMenu } from './UserMenu'

/** Pages outside the navigation still get a title in the top bar. */
const OTHER_TITLES: Record<string, string> = { '/import': 'Import', '/welcome': 'Get started' }

export function AppShell() {
  const { prefs, dispatch } = usePreferences()
  const [paletteOpen, setPaletteOpen] = useState(false)
  const pathname = useRouterState({ select: (s) => s.location.pathname })
  const collapsed = prefs.sidebarCollapsed
  useCommandPaletteShortcut(() => setPaletteOpen((o) => !o))

  // "[" toggles the sidebar, like many editors.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === '[' && !isTyping(e)) dispatch({ type: 'sidebar', collapsed: !collapsed })
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [collapsed, dispatch])

  const reduced = prefs.theme.motion === 'reduced' ? 'always' : prefs.theme.motion === 'full' ? 'never' : 'user'
  const nav = useNav()
  const current = nav.find((n) => pathname.startsWith(n.to))
  // The first page after loading the app appears at once; later ones fade in.
  const firstPage = useRef(true)
  useEffect(() => { firstPage.current = false }, [])

  return (
    <MotionConfig reducedMotion={reduced} transition={{ type: 'spring', stiffness: 420, damping: 38 }}>
      <TooltipPrimitive.Provider>
      <UtilityWindowsProvider>
        <div className="flex min-h-dvh bg-bg">
          <Sidebar collapsed={collapsed} onToggle={() => dispatch({ type: 'sidebar', collapsed: !collapsed })} pathname={pathname} />
          <div className="flex min-w-0 flex-1 flex-col">
            <Topbar title={current?.label ?? OTHER_TITLES[pathname] ?? (pathname.startsWith('/join/') ? 'Join a group' : '')} onSearch={() => setPaletteOpen(true)} />
            <AnnouncementBanner />
            <main className="mx-auto w-full max-w-[1400px] flex-1 px-4 pt-4 pb-28 sm:px-6 md:pb-10 lg:px-8">
              {/* The new page fades in; there is no fade-out. An exiting copy would still render <Outlet />,
                  which already shows the new route, so every page mounted twice and lost what was done in
                  its first moment (a file picked, a field typed) when the second copy replaced it. */}
              <motion.div
                key={pathname}
                initial={firstPage.current ? false : { opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.22, ease: [0.2, 0.8, 0.2, 1] }}
              >
                <Outlet />
              </motion.div>
            </main>
          </div>
          <MobileTabBar pathname={pathname} />
          <CommandPalette open={paletteOpen} onOpenChange={setPaletteOpen} />
          <EdgeDock />
        </div>
      </UtilityWindowsProvider>
      </TooltipPrimitive.Provider>
    </MotionConfig>
  )
}

function isTyping(e: KeyboardEvent) {
  const t = e.target as HTMLElement | null
  return !!t && (t.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(t.tagName))
}
export { isTyping }

function Sidebar({ collapsed, onToggle, pathname }: { collapsed: boolean; onToggle: () => void; pathname: string }) {
  const wide = useMediaQuery('(min-width: 1024px)')
  // Tablets always get the icon rail; desktops get what the user chose.
  const rail = collapsed || !wide
  const sections: [NavItem['section'], string | null][] = [['money', null], ['together', 'Together'], ['app', null]]
  const nav = useNav()
  return (
    <motion.aside
      initial={false}
      animate={{ width: rail ? 76 : 252 }}
      className="sticky top-0 hidden h-dvh shrink-0 flex-col overflow-hidden border-r border-border bg-surface md:flex"
    >
      <div className={cn('flex h-16 items-center px-4', rail ? 'justify-center' : 'justify-between')}>
        <Link to="/dashboard" aria-label="PayLedger home" className="rounded-control">
          <Logo withName={!rail} />
        </Link>
        {wide && !collapsed && (
          <Tooltip content={<span>Collapse <Kbd className="ml-1">[</Kbd></span>} side="right">
            <Button variant="ghost" size="icon-sm" onClick={onToggle} aria-label="Collapse sidebar"><PanelLeftClose /></Button>
          </Tooltip>
        )}
      </div>
      <div className={cn('px-3 pb-3', rail && 'flex justify-center')}>
        <AddMenu compact={rail} />
      </div>
      {/* Short windows (zoomed in, small laptops) get tighter rows, and the scrollbar only shows while pointing at the menu. */}
      <nav className="scroll-quiet flex flex-1 flex-col gap-5 overflow-y-auto px-3 py-2 [@media(max-height:800px)]:gap-2 [@media(max-height:800px)]:py-1" aria-label="Main">
        {sections.map(([section, heading]) => (
          <div key={section} className={cn('flex flex-col gap-0.5', section === 'app' && 'mt-auto')}>
            {heading && (rail
              ? <div className="mx-3 mb-1 h-px bg-border" />
              : <div className="px-3 pb-1 text-[0.6875rem] font-medium tracking-wider text-subtle uppercase">{heading}</div>)}
            {nav.filter((n) => n.section === section).map((item) => (
              <NavLink key={item.to} item={item} active={pathname.startsWith(item.to)} rail={rail} />
            ))}
          </div>
        ))}
      </nav>
      {wide && collapsed && (
        <div className="flex justify-center p-3">
          <Tooltip content={<span>Expand <Kbd className="ml-1">[</Kbd></span>} side="right">
            <Button variant="ghost" size="icon" onClick={onToggle} aria-label="Expand sidebar"><PanelLeftOpen /></Button>
          </Tooltip>
        </div>
      )}
    </motion.aside>
  )
}

/** Entries waiting in Auto-capture, on its menu item. */
function NavCount({ rail }: { rail?: boolean }) {
  const n = useCaptureCount().data?.pending ?? 0
  if (!n) return null
  return (
    <span className={cn('relative ml-auto rounded-full bg-accent px-1.5 text-[0.625rem] font-semibold leading-4 text-accent-fg tabular',
      rail && 'absolute top-0.5 right-0.5 ml-0')} aria-label={`${n} to review`}>{n > 99 ? '99+' : n}</span>
  )
}

function NavLink({ item, active, rail }: { item: NavItem; active: boolean; rail: boolean }) {
  const link = (
    <Link
      to={item.to}
      aria-label={rail ? item.label : undefined}
      className={cn(
        'group relative flex h-10 items-center gap-3 rounded-control px-3 text-sm font-medium transition-colors [@media(max-height:800px)]:h-8',
        active ? 'text-fg' : 'text-muted hover:bg-surface-2 hover:text-fg',
        rail && 'justify-center px-0',
      )}
      aria-current={active ? 'page' : undefined}
    >
      {active && (
        <motion.span layoutId="nav-active" className="absolute inset-0 rounded-control bg-accent-soft" transition={{ type: 'spring', stiffness: 500, damping: 40 }} />
      )}
      <item.icon className={cn('relative size-[18px] shrink-0', active && 'text-accent')} />
      {!rail && <span className="relative truncate">{item.label}</span>}
      {item.badge && <NavCount rail={rail} />}
    </Link>
  )
  return rail ? <Tooltip content={item.label} side="right">{link}</Tooltip> : link
}

function Topbar({ title, onSearch }: { title: string; onSearch: () => void }) {
  const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform)
  return (
    <header className="sticky top-0 z-30 flex h-16 items-center gap-3 border-b border-border bg-bg/80 px-4 backdrop-blur-xl sm:px-6 lg:px-8">
      <span className="md:hidden"><Logo withName={false} /></span>
      <h1 className="truncate text-[1.0625rem] font-semibold tracking-tight">{title}</h1>
      <SpaceSwitcher />
      <div className="ml-auto flex items-center gap-1.5">
        <button
          onClick={onSearch}
          className="hidden h-9 w-64 items-center gap-2 rounded-control border border-border bg-surface-2 px-3 text-sm text-subtle transition-colors hover:border-border-strong hover:text-muted sm:flex"
        >
          <Search className="size-4" />
          Search or jump to…
          <span className="ml-auto flex gap-0.5"><Kbd>{isMac ? '⌘' : 'Ctrl'}</Kbd><Kbd>K</Kbd></span>
        </button>
        <Button variant="ghost" size="icon" className="sm:hidden" onClick={onSearch} aria-label="Search"><Search /></Button>
        <SaveIndicator />
        <NotificationBell />
        <WidthToggle />
        <ThemeQuickToggle />
        <UserMenu />
      </div>
    </header>
  )
}

function SaveIndicator() {
  const { saveState } = usePreferences()
  const content: Record<typeof saveState, [ReactNode, string]> = {
    saving: [<Loader2 key="s" className="size-4 animate-spin" />, 'Saving your changes…'],
    saved: [<Check key="c" className="size-4" />, 'All changes saved to your account'],
    error: [<CloudOff key="e" className="size-4 text-critical" />, 'Not saved yet: will retry with your next change'],
  }
  const [icon, label] = content[saveState]
  return (
    <Tooltip content={label}>
      <span role="status" aria-label={label} className="hidden size-9 items-center justify-center text-subtle sm:inline-flex">{icon}</span>
    </Tooltip>
  )
}

function MobileTabBar({ pathname }: { pathname: string }) {
  const [more, setMore] = useState(false)
  const nav = useNav()
  const primary = nav.filter((n) => n.primary)
  const tab = (item: NavItem) => {
    const active = pathname.startsWith(item.to)
    return (
      <Link key={item.to} to={item.to} className={cn('flex flex-1 flex-col items-center gap-1 py-2 text-[0.6875rem] font-medium', active ? 'text-fg' : 'text-subtle')}>
        <item.icon className={cn('size-5', active && 'text-accent')} />
        {item.label}
      </Link>
    )
  }
  return (
    <>
      <nav className="fixed inset-x-0 bottom-0 z-30 flex items-stretch border-t border-border bg-bg/90 pb-[env(safe-area-inset-bottom)] backdrop-blur-xl md:hidden" aria-label="Main">
        {tab(primary[0]!)}
        {tab(primary[1]!)}
        <div className="flex flex-1 items-center justify-center">
          <AddMenu trigger={
            <button className="-mt-6 flex size-14 items-center justify-center rounded-full bg-accent text-accent-fg shadow-lift transition-transform active:scale-95" aria-label="Add">
              <Plus className="size-6" />
            </button>
          } />
        </div>
        {tab(primary[2]!)}
        <button onClick={() => setMore(true)} className="flex flex-1 flex-col items-center gap-1 py-2 text-[0.6875rem] font-medium text-subtle">
          <MoreHorizontal className="size-5" />
          More
        </button>
      </nav>
      <Dialog open={more} onOpenChange={setMore} title="More">
        <div className="grid grid-cols-3 gap-2 p-pad">
          {nav.filter((n) => !n.primary).map((item) => (
            <Link key={item.to} to={item.to} onClick={() => setMore(false)}
              className="flex flex-col items-center gap-2 rounded-card border border-border p-4 text-sm font-medium hover:bg-surface-2">
              <span className="relative"><item.icon className="size-5 text-muted" />{item.badge && <span className="absolute -top-2 -right-3"><NavCount /></span>}</span>
              {item.label}
            </Link>
          ))}
        </div>
      </Dialog>
    </>
  )
}
