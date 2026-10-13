import { Outlet, createRootRouteWithContext, createRoute, createRouter, lazyRouteComponent, redirect, useRouter } from '@tanstack/react-router'
import type { QueryClient } from '@tanstack/react-query'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/card'
import { DashboardPage } from '@/dashboard/DashboardPage'
import { AppShell } from '@/layout/AppShell'
import { ComingSoon, COMING } from '@/pages/ComingSoon'
import { SETTINGS_TAB_KEYS } from '@/pages/settings/tabs'
import { PreferencesProvider } from '@/state/PreferencesProvider'
import { profileQuery, useProfile } from '@/state/profile'
import { sessionQuery } from '@/state/session'

const root = createRootRouteWithContext<{ queryClient: QueryClient }>()({ component: Outlet })

const landing = createRoute({
  getParentRoute: () => root,
  path: '/',
  beforeLoad: async ({ context }) => {
    const session = await context.queryClient.ensureQueryData(sessionQuery)
    if (session.authenticated) throw redirect({ to: '/dashboard' })
  },
  component: lazyRouteComponent(() => import('@/pages/Landing'), 'Landing'),
})

/** Everything behind sign-in. Loads the profile once; preferences come with it. */
const app = createRoute({
  getParentRoute: () => root,
  id: 'app',
  beforeLoad: async ({ context }) => {
    const session = await context.queryClient.ensureQueryData(sessionQuery)
    if (!session.authenticated) throw redirect({ to: '/' })
  },
  loader: ({ context }) => context.queryClient.ensureQueryData(profileQuery),
  pendingComponent: AppPending,
  // Without this, a failed profile load (a service restarting, a network blip) left the skeleton up forever.
  errorComponent: AppError,
  component: AppRoot,
})

function AppRoot() {
  const { data: profile } = useProfile()
  if (!profile) return <AppPending />
  return (
    <PreferencesProvider key={profile.id} initial={profile.preferences}>
      <AppShell />
    </PreferencesProvider>
  )
}

function AppError() {
  const router = useRouter()
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-3 px-6 text-center">
      <p className="text-lg font-semibold">PayLedger couldn't load</p>
      <p className="max-w-sm text-sm text-muted">Something on our side didn't answer. Your data is safe; try again in a moment.</p>
      <Button variant="primary" onClick={() => void router.invalidate()}>Try again</Button>
    </div>
  )
}

function AppPending() {
  return (
    <div className="flex min-h-dvh">
      <div className="hidden w-[252px] border-r border-border bg-surface md:block" />
      <div className="flex-1 space-y-4 p-8">
        <Skeleton className="h-8 w-64" />
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          {Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-48" />)}
        </div>
      </div>
    </div>
  )
}

const dashboard = createRoute({ getParentRoute: () => app, path: '/dashboard', component: DashboardPage })

const settings = createRoute({
  getParentRoute: () => app,
  path: '/settings',
  validateSearch: z.object({
    tab: z.enum(SETTINGS_TAB_KEYS).optional().catch(undefined),
  }),
  component: lazyRouteComponent(() => import('@/pages/settings/SettingsPage'), 'SettingsPage'),
})

const accounts = createRoute({
  getParentRoute: () => app,
  path: '/accounts',
  component: lazyRouteComponent(() => import('@/pages/money/AccountsPage'), 'AccountsPage'),
})

const transactions = createRoute({
  getParentRoute: () => app,
  path: '/transactions',
  // ?add=EXPENSE opens the add dialog (the sidebar's Add menu links here); ?account= filters.
  validateSearch: z.object({
    add: z.enum(['EXPENSE', 'INCOME', 'TRANSFER']).optional().catch(undefined),
    account: z.string().uuid().optional().catch(undefined),
  }),
  component: lazyRouteComponent(() => import('@/pages/money/TransactionsPage'), 'TransactionsPage'),
})

const comingSoon = (Object.keys(COMING) as (keyof typeof COMING)[]).map((page) =>
  createRoute({ getParentRoute: () => app, path: `/${page}`, component: () => <ComingSoon page={page} /> }))

const routeTree = root.addChildren([landing, app.addChildren([dashboard, settings, accounts, transactions, ...comingSoon])])

export function createAppRouter(queryClient: QueryClient) {
  return createRouter({
    routeTree,
    context: { queryClient },
    defaultPreload: 'intent',
    defaultNotFoundComponent: () => (
      <div className="flex min-h-[60dvh] flex-col items-center justify-center gap-2 text-center">
        <div className="text-5xl font-semibold tracking-tight">404</div>
        <p className="text-muted">This page doesn't exist.</p>
        <a href="/" className="text-sm underline underline-offset-4">Go home</a>
      </div>
    ),
  })
}

declare module '@tanstack/react-router' {
  interface Register {
    router: ReturnType<typeof createAppRouter>
  }
}
