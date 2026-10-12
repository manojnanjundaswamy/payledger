import { useEffect, useMemo, useState } from 'react'
import { motion } from 'motion/react'
import { ArrowRight, CalendarClock, Home, IndianRupee, Moon, PieChart, Split, Sun, Wallet } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { WidgetEnvContext, type WidgetEnv } from '@/dashboard/context'
import { WIDGETS } from '@/dashboard/widgets'
import { formatMoney, formatMoneyShort, formatMoneyWhole } from '@/lib/money'
import { Logo } from '@/layout/Logo'
import { defaultTheme, parsePreferences } from '@/state/preferences'
import { useSession } from '@/state/session'
import { applyTheme, resolveMode } from '@/theme/engine'

const FEATURES = [
  { icon: Wallet, title: 'Safe to spend, every day', text: 'What is really left after EMIs, card dues, SIPs and savings: not just your bank balance.' },
  { icon: Split, title: 'Split with anyone', text: 'Trips and flatmates by link. Friends join without an account; your share lands in your budget.' },
  { icon: Home, title: 'Household budgets', text: 'Spouse, kids, parents: one plan for the family, with private money kept private.' },
  { icon: CalendarClock, title: 'No surprise bills', text: 'Insurance, school fees, Diwali: spread the big yearly costs into small monthly set-asides.' },
  { icon: PieChart, title: 'Your dashboard, your way', text: 'Drag, resize and arrange widgets. Black and white, or any accent colour you like.' },
  { icon: IndianRupee, title: 'Made for India', text: '₹ and lakh/crore, UPI-friendly categories, April–March financial year reports.' },
]

export function Landing() {
  const { data: session } = useSession()
  const [mode, setMode] = useState(() => resolveMode(cachedMode()))
  useEffect(() => {
    applyTheme({ ...defaultTheme(), mode })
  }, [mode])
  const loginError = new URLSearchParams(location.search).has('loginError')

  return (
    <div className="min-h-dvh bg-bg">
      <header className="mx-auto flex h-16 max-w-6xl items-center justify-between px-4 sm:px-6">
        <Logo />
        <div className="flex items-center gap-2">
          <Button variant="ghost" size="icon" aria-label="Toggle theme" onClick={() => setMode(mode === 'dark' ? 'light' : 'dark')}>
            {mode === 'dark' ? <Sun /> : <Moon />}
          </Button>
          <Button variant="ghost" asChild><a href={session?.loginUrl ?? '/oauth2/authorization/keycloak'}>Sign in</a></Button>
        </div>
      </header>

      <main className="mx-auto max-w-6xl px-4 sm:px-6">
        {loginError && (
          <div role="alert" className="mt-4 rounded-card border border-critical/30 bg-critical/10 px-4 py-3 text-sm">
            Signing in didn't work. Please try again.
          </div>
        )}
        <section className="flex flex-col items-center pt-14 pb-16 text-center sm:pt-24">
          <motion.div initial={{ opacity: 0, y: 14 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.5, ease: [0.2, 0.8, 0.2, 1] }} className="flex flex-col items-center">
            <span className="mb-5 rounded-full border border-border px-3 py-1 text-xs text-muted">Budgets · Households · Splitting</span>
            <h1 className="max-w-3xl text-[2.5rem] leading-[1.05] font-semibold tracking-tight text-balance sm:text-6xl">
              Your money, and everyone you share it with.
            </h1>
            <p className="mt-5 max-w-xl text-lg text-muted text-balance">
              Budget your month, run the household, split trips with friends: one place, one clear picture, built for how money really moves in India.
            </p>
            <div className="mt-8 flex flex-col gap-3 sm:flex-row">
              <Button variant="primary" size="lg" asChild>
                <a href={session?.signupUrl ?? '/oauth2/authorization/keycloak?signup'}>Create your free account<ArrowRight /></a>
              </Button>
              <Button variant="outline" size="lg" asChild>
                <a href={session?.loginUrl ?? '/oauth2/authorization/keycloak'}>I already have an account</a>
              </Button>
            </div>
          </motion.div>
          <PreviewBoard />
        </section>

        <section className="grid gap-3 pb-20 sm:grid-cols-2 lg:grid-cols-3">
          {FEATURES.map((f, i) => (
            <motion.div key={f.title} initial={{ opacity: 0, y: 16 }} whileInView={{ opacity: 1, y: 0 }} viewport={{ once: true, margin: '-40px' }} transition={{ delay: (i % 3) * 0.06 }}
              className="rounded-card border border-border bg-surface p-6">
              <f.icon className="size-5" />
              <h3 className="mt-4 font-semibold">{f.title}</h3>
              <p className="mt-1.5 text-sm text-muted">{f.text}</p>
            </motion.div>
          ))}
        </section>
      </main>
      <footer className="border-t border-border py-8 text-center text-xs text-subtle">PayLedger · a learning project, made in India</footer>
    </div>
  )
}

/** The real dashboard widgets, with sample data, as the hero image. */
function PreviewBoard() {
  const env = useMemo<WidgetEnv>(() => ({
    sample: true,
    prefs: parsePreferences({}),
    profile: {
      id: 'preview', email: 'you@example.com', displayName: 'You', preferences: {}, spaces: [],
      settings: { locale: 'en-IN', timeZone: 'Asia/Kolkata', currency: 'INR', budgetStartDay: 1 },
    },
    money: (minor, opts = {}) => (opts.compact ? formatMoneyShort(minor) : opts.whole ? formatMoneyWhole(minor) : formatMoney(minor, 'INR', 'en-IN', opts)),
  }), [])
  const tiles = [['safeToSpend', 'sm:col-span-1'], ['budgets', 'sm:col-span-1'], ['cashFlow', 'sm:col-span-2']] as const
  return (
    <WidgetEnvContext.Provider value={env}>
      <motion.div initial={{ opacity: 0, y: 30 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.15, duration: 0.6, ease: [0.2, 0.8, 0.2, 1] }}
        className="pointer-events-none mt-16 w-full max-w-4xl rounded-[calc(var(--radius)+10px)] border border-border bg-surface-2 p-3 shadow-lift select-none sm:p-4" aria-hidden>
        <div className="grid gap-3 text-left sm:grid-cols-2">
          {tiles.map(([type, span]) => {
            const meta = WIDGETS[type]
            const Body = meta.component
            return (
              <section key={type} className={`@container flex flex-col rounded-card border border-border bg-surface p-pad shadow-card ${span}`}>
                <div className="mb-3 flex items-center gap-2 text-sm font-semibold"><meta.icon className="size-4 text-muted" />{meta.name}</div>
                <div className="flex-1"><Body widget={{ id: type, type, size: 'm' }} dashboardId="preview" editing={false} /></div>
              </section>
            )
          })}
        </div>
      </motion.div>
    </WidgetEnvContext.Provider>
  )
}

function cachedMode(): 'light' | 'dark' | 'system' {
  try {
    return JSON.parse(localStorage.getItem('payledger.theme') ?? 'null')?.mode ?? 'system'
  } catch {
    return 'system'
  }
}
