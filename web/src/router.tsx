import { Outlet, createRootRouteWithContext, createRoute, createRouter, lazyRouteComponent, redirect, useRouter } from '@tanstack/react-router'
import type { QueryClient } from '@tanstack/react-query'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/card'
import { DashboardPage } from '@/dashboard/DashboardPage'
import { AppShell } from '@/layout/AppShell'
import { ADMIN_TABS_KEYS } from '@/pages/admin/tabs'
import { TOOL_IDS } from '@/utilities/registry'
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

/** Guests (no account): a join link, then their groups. Outside the app shell; their own session cookie. */
const guestJoin = createRoute({
  getParentRoute: () => root,
  path: '/guest/join/$token',
  beforeLoad: async ({ context, params }) => {
    const session = await context.queryClient.ensureQueryData(sessionQuery)
    if (session.authenticated) throw redirect({ to: '/join/$token', params: { token: params.token } })
  },
  component: lazyRouteComponent(() => import('@/pages/guest/GuestJoinPage'), 'GuestJoinPage'),
})

const guestGroup = createRoute({
  getParentRoute: () => root,
  path: '/guest/groups/$groupId',
  component: lazyRouteComponent(() => import('@/pages/guest/GuestGroupPage'), 'GuestGroupPage'),
})

/** Everything behind sign-in. Loads the profile once; preferences come with it. */
const app = createRoute({
  getParentRoute: () => root,
  id: 'app',
  beforeLoad: async ({ context, location }) => {
    const session = await context.queryClient.ensureQueryData(sessionQuery)
    if (!session.authenticated) {
      // An invite link opened while signed out: sign in (and come back to it), or join as a guest.
      if (location.pathname.startsWith('/join/')) throw redirect({ to: '/guest/join/$token', params: { token: location.pathname.slice('/join/'.length) } })
      throw redirect({ to: '/' })
    }
    const after = recall()
    if (after && after !== location.pathname) throw redirect({ href: after })
  },
  loader: ({ context }) => context.queryClient.ensureQueryData(profileQuery),
  pendingComponent: AppPending,
  // Without this, a failed profile load (a service restarting, a network blip) left the skeleton up forever.
  errorComponent: AppError,
  component: AppRoot,
})

const AFTER_SIGN_IN = 'after-sign-in'

// Written by the guest join page (GuestJoinPage) when someone chooses to sign in from a join link.
/** The page to return to after signing in, once. */
function recall(): string | null {
  try {
    const path = sessionStorage.getItem(AFTER_SIGN_IN)
    sessionStorage.removeItem(AFTER_SIGN_IN)
    return path && path.startsWith('/join/') ? path : null
  } catch {
    return null
  }
}

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

const categories = createRoute({
  getParentRoute: () => app,
  path: '/categories',
  component: lazyRouteComponent(() => import('@/pages/money/CategoriesPage'), 'CategoriesPage'),
})

const budgets = createRoute({
  getParentRoute: () => app,
  path: '/budgets',
  // ?month=2026-10 shows another budget month; none: the current one.
  validateSearch: z.object({
    month: z.string().regex(/^\d{4}-(0[1-9]|1[0-2])$/).optional().catch(undefined),
  }),
  component: lazyRouteComponent(() => import('@/pages/money/BudgetsPage'), 'BudgetsPage'),
})

const goals = createRoute({
  getParentRoute: () => app,
  path: '/goals',
  component: lazyRouteComponent(() => import('@/pages/money/GoalsPage'), 'GoalsPage'),
})

const plan = createRoute({
  getParentRoute: () => app,
  path: '/plan',
  // ?open=<commitment id> opens that bill or saving (from the dashboard or the calendar).
  // ?due=2026-10-21 starts a new bill due that day (from any calendar).
  validateSearch: z.object({
    open: z.string().uuid().optional().catch(undefined),
    due: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional().catch(undefined),
  }),
  component: lazyRouteComponent(() => import('@/pages/money/PlanPage'), 'PlanPage'),
})

const importPage = createRoute({
  getParentRoute: () => app,
  path: '/import',
  component: lazyRouteComponent(() => import('@/pages/money/ImportPage'), 'ImportPage'),
})

const reports = createRoute({
  getParentRoute: () => app,
  path: '/reports',
  validateSearch: z.object({
    period: z.enum(['this-month', 'last-month', 'this-fy', 'last-fy', 'last-12', 'custom']).optional().catch(undefined),
    from: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional().catch(undefined),
    to: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional().catch(undefined),
    // The same filters as the transactions list.
    account: z.string().uuid().optional().catch(undefined),
    category: z.string().uuid().optional().catch(undefined),
    tag: z.string().max(40).optional().catch(undefined),
    q: z.string().max(100).optional().catch(undefined),
  }),
  component: lazyRouteComponent(() => import('@/pages/money/ReportsPage'), 'ReportsPage'),
})

const groups = createRoute({
  getParentRoute: () => app,
  path: '/groups',
  component: lazyRouteComponent(() => import('@/pages/groups/GroupsPage'), 'GroupsPage'),
})

const capture = createRoute({
  getParentRoute: () => app,
  path: '/capture',
  // Android's share sheet opens /capture?title=…&text=… (the manifest's share_target).
  validateSearch: z.object({
    text: z.string().max(20_000).optional().catch(undefined), title: z.string().max(500).optional().catch(undefined),
    method: z.enum(['email', 'paste', 'share', 'statement']).optional().catch(undefined),
  }),
  component: lazyRouteComponent(() => import('@/pages/capture/CapturePage'), 'CapturePage'),
})

const tax = createRoute({
  getParentRoute: () => app,
  path: '/tax',
  component: lazyRouteComponent(() => import('@/pages/money/TaxPage'), 'TaxPage'),
})

const households = createRoute({
  getParentRoute: () => app,
  path: '/household',
  component: lazyRouteComponent(() => import('@/pages/household/HouseholdPage'), 'HouseholdsPage'),
})

const householdJoin = createRoute({
  getParentRoute: () => app,
  path: '/household/join',
  validateSearch: z.object({ token: z.string().max(200).optional().catch(undefined) }),
  component: lazyRouteComponent(() => import('@/pages/household/HouseholdPage'), 'JoinHouseholdPage'),
})

const household = createRoute({
  getParentRoute: () => app,
  path: '/household/$householdId',
  component: lazyRouteComponent(() => import('@/pages/household/HouseholdPage'), 'HouseholdDetail'),
})

const people = createRoute({
  getParentRoute: () => app,
  path: '/people',
  component: lazyRouteComponent(() => import('@/pages/people/PeoplePage'), 'PeoplePage'),
})

const group = createRoute({
  getParentRoute: () => app,
  path: '/groups/$groupId',
  // ?tab= picks Overview, Activity, Calendar or People; ?month= the calendar's month.
  validateSearch: z.object({
    tab: z.enum(['overview', 'activity', 'calendar', 'people']).optional().catch(undefined),
    month: z.string().regex(/^\d{4}-(0[1-9]|1[0-2])$/).optional().catch(undefined),
  }),
  component: lazyRouteComponent(() => import('@/pages/groups/GroupPage'), 'GroupPage'),
})

const join = createRoute({
  getParentRoute: () => app,
  path: '/join/$token',
  component: lazyRouteComponent(() => import('@/pages/groups/JoinPage'), 'JoinPage'),
})

const transactions = createRoute({
  getParentRoute: () => app,
  path: '/transactions',
  // ?add=EXPENSE opens the add dialog (the sidebar's Add menu links here); ?account=, ?category= (with its
  // subcategories) and ?from=&to= filter, which is how a report drills down to its transactions.
  validateSearch: z.object({
    add: z.enum(['EXPENSE', 'INCOME', 'TRANSFER']).optional().catch(undefined),
    open: z.string().uuid().optional().catch(undefined),
    date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional().catch(undefined),
    account: z.string().uuid().optional().catch(undefined),
    category: z.string().uuid().optional().catch(undefined),
    from: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional().catch(undefined),
    to: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional().catch(undefined),
    // Search: words, type, amount range in rupees, a tag; and the list or calendar view.
    q: z.string().max(100).optional().catch(undefined),
    kind: z.enum(['EXPENSE', 'INCOME', 'TRANSFER']).optional().catch(undefined),
    min: z.coerce.number().nonnegative().optional().catch(undefined),
    max: z.coerce.number().nonnegative().optional().catch(undefined),
    tag: z.string().max(40).optional().catch(undefined),
    goal: z.string().uuid().optional().catch(undefined),
    person: z.string().max(80).optional().catch(undefined),
    view: z.enum(['list', 'calendar']).optional().catch(undefined),
    month: z.string().regex(/^\d{4}-(0[1-9]|1[0-2])$/).optional().catch(undefined),
  }),
  component: lazyRouteComponent(() => import('@/pages/money/TransactionsPage'), 'TransactionsPage'),
})

const admin = createRoute({
  getParentRoute: () => app,
  path: '/admin',
  validateSearch: z.object({
    tab: z.enum(ADMIN_TABS_KEYS).optional().catch(undefined),
    // Users tab filter, so the dashboard's numbers open the people behind them.
    show: z.enum(['all', 'PREMIUM', 'TRIAL', 'GRANTED', 'FREE', 'DISABLED', 'errors']).optional().catch(undefined),
  }),
  component: lazyRouteComponent(() => import('@/pages/admin/AdminPage'), 'AdminPage'),
})

const welcome = createRoute({
  getParentRoute: () => app,
  path: '/welcome',
  component: lazyRouteComponent(() => import('@/pages/money/SetupPage'), 'SetupPage'),
})

const utilities = createRoute({
  getParentRoute: () => app,
  path: '/utilities',
  validateSearch: z.object({ tool: z.enum(TOOL_IDS as [string, ...string[]]).optional().catch(undefined) }),
  component: lazyRouteComponent(() => import('@/pages/UtilitiesPage'), 'UtilitiesPage'),
})

const routeTree = root.addChildren([landing, guestJoin, guestGroup, app.addChildren([dashboard, settings, accounts, categories, budgets, plan, goals, importPage, reports, groups, group, people, tax, capture, households, householdJoin, household, join, transactions, admin, welcome, utilities])])

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
