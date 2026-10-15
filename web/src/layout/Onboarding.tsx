import { useCallback, useEffect, useLayoutEffect, useState } from 'react'
import { AnimatePresence, motion } from 'motion/react'
import { ArrowLeft, ArrowRight, Bell, CalendarCheck, Compass, IndianRupee, PieChart, Plus, Sparkles, Users, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Segmented } from '@/components/ui/controls'
import { ThemePalettePicker } from '@/components/ThemePalettePicker'
import { cn } from '@/lib/cn'
import { usePreferences } from '@/state/PreferencesProvider'
import { useProfile } from '@/state/profile'
import { Logo } from './Logo'
import { RegionSetup } from './RegionSetup'

/** Where the tour points, in order. Steps whose target isn't on screen (phone vs desktop, one space only) are left out. */
const STEPS: { target: string; title: string; text: string }[] = [
  { target: '[data-tour="nav"]', title: 'Your pages', text: 'Everything lives here: transactions, budgets, the plan, goals, groups and reports. On a phone, the ones you use most are at the bottom and the rest are under More.' },
  { target: '[data-tour="add"]', title: 'Add anything', text: 'An expense, an income, a transfer, a bill, or an expense you shared with friends. From any page. If a list is filtered, the form starts with that filter.' },
  { target: '[data-testid="widget-grid"]', title: 'Your dashboard', text: 'Safe to spend, budgets, bills due and goals at a glance. Click any number to see what makes it up. Customize moves, resizes and adds cards.' },
  { target: '[data-tour="space"]', title: 'Personal or household', text: 'Switch between your own money and a household you share. Everything you see follows the choice.' },
  { target: '[data-tour="search"]', title: 'Search or jump', text: 'Find any page or action in a few letters. Ctrl K opens it from anywhere.' },
  { target: '[data-tour="bell"]', title: 'Notifications', text: 'Bills due, money shared with you, settle-ups and what auto-capture found. The number shows what you haven’t read.' },
  { target: '[data-tour="look"]', title: 'Your look', text: 'Light, dark or match your device, in Classic, Bumblebee yellow or Ferrari orange.' },
  { target: '[data-tour="tools"]', title: 'Calculators at the edge', text: 'Loan EMI, income tax, GST, currency and a calculator. Open one, keep it floating, and add its result as an entry.' },
  { target: '[data-tour="you"]', title: 'You', text: 'Settings, your plan, download or restore your data, and sign out.' },
]

const SEEN = 'payledger.tour.seen'
const REGION_LATER = 'payledger.region.later'
/** End-to-end tests set this so the welcome and tour don't cover what they click (the tour test clears it). */
const TESTING = 'payledger.e2e.noTour'

function stored(storage: () => Storage, key: string) {
  try { return storage().getItem(key) } catch { return null }
}
function store(storage: () => Storage, key: string) {
  try { storage().setItem(key, '1') } catch { /* private mode: the tour may show again */ }
}

/**
 * The first sign-in: "Make it yours" (look and feel, once), then the tour. The tour is offered at each sign-in until
 * "Don't show this again" is ticked; once seen in a session (this tab), it waits for the next one.
 */
export function Onboarding({ pathname }: { pathname: string }) {
  const { prefs, dispatch } = usePreferences()
  const { data: profile } = useProfile()
  const [stage, setStage] = useState<'region' | 'welcome' | 'intro' | 'tour' | null>(() => {
    if (stored(() => localStorage, TESTING)) return null
    // Region first: money, dates and months can't be right without it. "Later" waits until the next sign-in.
    if (!profile?.settings.country && !stored(() => sessionStorage, REGION_LATER)) return 'region'
    if (!prefs.welcomed) return 'welcome'
    return !prefs.tourOff && !stored(() => sessionStorage, SEEN) ? 'intro' : null
  })
  const [dontShow, setDontShow] = useState(false)
  // Pages that are a flow of their own (setting up, joining by link) come first; the tour waits.
  const paused = pathname === '/welcome' || pathname.startsWith('/join') || pathname.startsWith('/household/join')
  if (paused || !stage) return null

  const close = () => {
    store(() => sessionStorage, SEEN)
    if (dontShow) dispatch({ type: 'onboarding', patch: { tourOff: true } })
    setStage(null)
  }
  return (
    <AnimatePresence>
      {stage === 'region' && <RegionSetup key="region"
        onDone={() => setStage(!prefs.welcomed ? 'welcome' : !prefs.tourOff && !stored(() => sessionStorage, SEEN) ? 'intro' : null)}
        onLater={() => { store(() => sessionStorage, REGION_LATER); setStage(!prefs.welcomed ? 'welcome' : null) }} />}
      {stage === 'welcome' && <MakeItYours key="welcome" onDone={() => { dispatch({ type: 'onboarding', patch: { welcomed: true } }); setStage('intro') }} />}
      {stage === 'intro' && <TourIntro key="intro" dontShow={dontShow} onDontShow={setDontShow} onSkip={close} onStart={() => setStage('tour')} />}
      {stage === 'tour' && <Tour key="tour" dontShow={dontShow} onDontShow={setDontShow} onClose={close} />}
    </AnimatePresence>
  )
}

/** Look and feel first: every choice applies to the whole app at once, so the page behind is the preview. */
function MakeItYours({ onDone }: { onDone: () => void }) {
  const { prefs, dispatch } = usePreferences()
  const name = useProfile().data?.displayName?.split(' ')[0]
  const theme = prefs.theme
  return (
    <motion.div role="dialog" aria-modal aria-label="Make it yours" data-testid="make-it-yours"
      initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
      className="fixed inset-0 z-[70] overflow-y-auto bg-bg/90 backdrop-blur-xl">
      <div className="mx-auto flex min-h-full max-w-3xl flex-col gap-6 px-4 py-8 sm:px-6 sm:py-12">
        <div className="relative flex flex-col items-center gap-3 text-center">
          <motion.div aria-hidden className="pointer-events-none absolute -top-24 left-1/2 size-[28rem] -translate-x-1/2 rounded-full bg-accent opacity-20 blur-3xl"
            animate={{ scale: [0.9, 1.05, 0.9] }} transition={{ duration: 6, repeat: Infinity, ease: 'easeInOut' }} />
          <Coins />
          <motion.div initial={{ scale: 0.4, rotate: -20, opacity: 0 }} animate={{ scale: 1, rotate: 0, opacity: 1 }}
            transition={{ type: 'spring', stiffness: 260, damping: 16, delay: 0.1 }}
            className="flex size-16 items-center justify-center rounded-2xl bg-surface shadow-lift"><Logo withName={false} /></motion.div>
          <motion.h2 initial={{ y: 12, opacity: 0 }} animate={{ y: 0, opacity: 1 }} transition={{ delay: 0.25 }}
            className="text-2xl font-semibold tracking-tight sm:text-3xl">{name ? `Welcome, ${name}` : 'Welcome'}. Make it yours.</motion.h2>
          <motion.p initial={{ y: 12, opacity: 0 }} animate={{ y: 0, opacity: 1 }} transition={{ delay: 0.35 }} className="max-w-md text-sm text-muted">
            Pick how PayLedger looks. Everything changes as you choose, and you can change it later in Settings.
          </motion.p>
        </div>

        <motion.section initial={{ y: 16, opacity: 0 }} animate={{ y: 0, opacity: 1 }} transition={{ delay: 0.45 }}
          className="flex flex-col gap-5 rounded-card border border-border bg-surface p-pad shadow-card">
          <div className="flex flex-col gap-2">
            <h3 className="text-sm font-semibold">Theme palette</h3>
            <ThemePalettePicker palette={theme.palette} mode={theme.mode} onChange={(patch) => dispatch({ type: 'theme', patch })} />
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="flex flex-col gap-2">
              <h3 className="text-sm font-semibold">Text size</h3>
              <Segmented label="Text size" value={theme.textSize} onChange={(v) => dispatch({ type: 'theme', patch: { textSize: v } })} className="self-start"
                options={[{ value: 'sm', label: 'Smaller' }, { value: 'md', label: 'Standard' }, { value: 'lg', label: 'Larger' }]} />
            </div>
            <div className="flex flex-col gap-2">
              <h3 className="text-sm font-semibold">Spacing</h3>
              <Segmented label="Spacing" value={theme.density} onChange={(v) => dispatch({ type: 'theme', patch: { density: v } })} className="self-start"
                options={[{ value: 'comfortable', label: 'Roomy' }, { value: 'compact', label: 'Compact' }]} />
            </div>
          </div>
        </motion.section>

        <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ delay: 0.6 }} className="flex flex-wrap items-center justify-end gap-2">
          <Button variant="ghost" onClick={onDone}>Keep the defaults</Button>
          <Button variant="primary" size="lg" onClick={onDone}>Looks good<ArrowRight /></Button>
        </motion.div>
      </div>
    </motion.div>
  )
}

/** A few coins and icons drifting up behind the greeting. */
function Coins() {
  const icons = [IndianRupee, PieChart, CalendarCheck, Users, Bell, Sparkles]
  return (
    <div aria-hidden className="pointer-events-none absolute inset-x-0 -top-4 h-40 overflow-hidden">
      {icons.map((Icon, i) => (
        <motion.span key={i} className="absolute flex size-8 items-center justify-center rounded-full bg-accent-soft text-accent"
          style={{ left: `${8 + i * 16}%` }}
          initial={{ y: 140, opacity: 0, rotate: -30 }}
          animate={{ y: [140, 20 + (i % 3) * 18, 30 + (i % 3) * 18], opacity: [0, 1, 0.55], rotate: 0 }}
          transition={{ duration: 1.6, delay: 0.15 + i * 0.08, ease: 'easeOut' }}>
          <Icon className="size-4" />
        </motion.span>
      ))}
    </div>
  )
}

function DontShow({ checked, onChange }: { checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <label className="flex cursor-pointer items-center gap-2 text-xs text-muted">
      <input type="checkbox" className="size-4 accent-[var(--accent)]" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      Don&rsquo;t show this again
    </label>
  )
}

function TourIntro({ dontShow, onDontShow, onSkip, onStart }: { dontShow: boolean; onDontShow: (v: boolean) => void; onSkip: () => void; onStart: () => void }) {
  return (
    <motion.div className="fixed inset-0 z-[70] flex items-center justify-center bg-black/50 p-4 backdrop-blur-sm"
      initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
      <motion.div role="dialog" aria-modal aria-label="Take the tour" data-testid="tour-intro"
        initial={{ scale: 0.92, y: 16 }} animate={{ scale: 1, y: 0 }} exit={{ scale: 0.96, opacity: 0 }}
        className="relative flex w-full max-w-sm flex-col items-center gap-4 overflow-hidden rounded-card border border-border bg-surface p-6 text-center shadow-lift">
        <div className="relative flex size-24 items-center justify-center">
          <motion.span className="absolute inset-0 rounded-full border-2 border-dashed border-accent/40"
            animate={{ rotate: 360 }} transition={{ duration: 14, repeat: Infinity, ease: 'linear' }} />
          {[Plus, PieChart, Users, Bell].map((Icon, i) => (
            <motion.span key={i} className="absolute flex size-7 items-center justify-center rounded-full bg-surface-2 text-accent shadow-card"
              style={{ top: i === 0 ? -6 : i === 2 ? 'auto' : '50%', bottom: i === 2 ? -6 : undefined, left: i === 3 ? -6 : i === 1 ? 'auto' : '50%', right: i === 1 ? -6 : undefined, marginTop: i % 2 ? -14 : 0, marginLeft: i % 2 ? 0 : -14 }}
              initial={{ scale: 0 }} animate={{ scale: 1 }} transition={{ delay: 0.2 + i * 0.1, type: 'spring', stiffness: 400, damping: 14 }}>
              <Icon className="size-3.5" />
            </motion.span>
          ))}
          <motion.span className="flex size-12 items-center justify-center rounded-full bg-accent text-accent-fg"
            animate={{ scale: [1, 1.08, 1] }} transition={{ duration: 2, repeat: Infinity }}><Compass className="size-6" /></motion.span>
        </div>
        <div>
          <h2 className="text-lg font-semibold">A one-minute tour?</h2>
          <p className="mt-1 text-sm text-muted">See where everything is: adding entries, your dashboard, households, search, notifications and the handy calculators.</p>
        </div>
        <div className="flex w-full gap-2">
          <Button variant="ghost" className="flex-1" onClick={onSkip}>Skip</Button>
          <Button variant="primary" className="flex-1" onClick={onStart}>Start the tour<ArrowRight /></Button>
        </div>
        <DontShow checked={dontShow} onChange={onDontShow} />
      </motion.div>
    </motion.div>
  )
}

interface Box { top: number; left: number; width: number; height: number }
const SPRING = { type: 'spring', stiffness: 320, damping: 32 } as const

function visible(selector: string): HTMLElement | null {
  for (const el of document.querySelectorAll<HTMLElement>(selector)) {
    const r = el.getBoundingClientRect()
    if (r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== 'hidden') return el
  }
  return null
}

/** A spotlight on one part of the screen at a time, with a card saying what it's for. */
function Tour({ dontShow, onDontShow, onClose }: { dontShow: boolean; onDontShow: (v: boolean) => void; onClose: () => void }) {
  const [steps] = useState(() => STEPS.filter((s) => visible(s.target)))
  const [index, setIndex] = useState(0)
  const [box, setBox] = useState<Box | null>(null)
  const [view, setView] = useState({ w: window.innerWidth, h: window.innerHeight })
  const step = steps[index]

  const measure = useCallback(() => {
    setView({ w: window.innerWidth, h: window.innerHeight })
    const el = step && visible(step.target)
    if (!el) return setBox(null)
    const r = el.getBoundingClientRect()
    const pad = 6
    // A tall target (the dashboard) is shown from its top, so the card still has room.
    const height = Math.min(r.height + pad * 2, window.innerHeight * 0.55)
    setBox({ top: r.top - pad, left: r.left - pad, width: r.width + pad * 2, height })
  }, [step])

  useLayoutEffect(() => {
    const el = step && visible(step.target)
    if (el) {
      const r = el.getBoundingClientRect()
      if (r.top < 64 || r.top > window.innerHeight - 120) el.scrollIntoView({ block: 'start', behavior: 'instant' as ScrollBehavior })
    }
    measure()
  }, [step, measure])

  useEffect(() => {
    window.addEventListener('resize', measure)
    window.addEventListener('scroll', measure, true)
    return () => { window.removeEventListener('resize', measure); window.removeEventListener('scroll', measure, true) }
  }, [measure])

  const last = index === steps.length - 1
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
      else if (e.key === 'ArrowRight') setIndex((i) => Math.min(i + 1, steps.length - 1))
      else if (e.key === 'ArrowLeft') setIndex((i) => Math.max(i - 1, 0))
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose, steps.length])

  // Nothing to point at (an unusual screen): end quietly.
  useEffect(() => { if (!step) onClose() }, [step, onClose])
  if (!step) return null
  // The card goes below the target if there's room, else above; always inside the screen with a 16px gutter.
  const cardWidth = Math.min(340, view.w - 32)
  const below = !box || box.top + box.height + 220 < view.h
  const cardTop = !box ? view.h / 2 - 100 : below ? box.top + box.height + 12 : undefined
  const cardBottom = box && !below ? view.h - box.top + 12 : undefined
  const cardLeft = Math.max(16, Math.min((box ? box.left + box.width / 2 : view.w / 2) - cardWidth / 2, view.w - cardWidth - 16))

  return (
    <motion.div className="fixed inset-0 z-[70]" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} data-testid="tour">
      {/* The page dims except for a hole over the target, which glides from one step to the next. */}
      <svg aria-hidden className="fixed inset-0 size-full">
        <defs>
          <mask id="tour-hole">
            <rect width="100%" height="100%" fill="white" />
            {box && <motion.rect rx={12} fill="black" initial={false} animate={{ x: box.left, y: box.top, width: box.width, height: box.height }} transition={SPRING} />}
          </mask>
        </defs>
        <rect width="100%" height="100%" fill="black" fillOpacity={0.6} mask="url(#tour-hole)" />
      </svg>
      {box && (
        <motion.div aria-hidden className="pointer-events-none fixed rounded-[12px] ring-2 ring-accent ring-offset-0"
          initial={false} animate={{ top: box.top, left: box.left, width: box.width, height: box.height }} transition={SPRING} />
      )}
      <motion.div role="dialog" aria-modal aria-label={step.title} key={index}
        initial={{ opacity: 0, y: below ? 8 : -8 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.12 }}
        className="fixed flex flex-col gap-3 rounded-card border border-border bg-surface p-4 shadow-lift"
        style={{ top: cardTop, bottom: cardBottom, left: cardLeft, width: cardWidth }}>
        <div className="flex items-start justify-between gap-2">
          <div>
            <p className="text-[0.6875rem] font-medium tracking-wider text-subtle uppercase">{index + 1} of {steps.length}</p>
            <h2 className="text-base font-semibold">{step.title}</h2>
          </div>
          <Button variant="ghost" size="icon-sm" onClick={onClose} aria-label="End the tour"><X /></Button>
        </div>
        <p className="text-sm text-muted">{step.text}</p>
        <div className="flex gap-1" aria-hidden>
          {steps.map((s, i) => <span key={s.target} className={cn('h-1 flex-1 rounded-full transition-colors', i <= index ? 'bg-accent' : 'bg-surface-2')} />)}
        </div>
        <div className="flex items-center justify-between gap-2">
          {last ? <DontShow checked={dontShow} onChange={onDontShow} /> : <button type="button" className="text-xs text-subtle hover:text-fg" onClick={onClose}>Skip tour</button>}
          <div className="flex gap-2">
            {index > 0 && <Button variant="ghost" size="sm" onClick={() => setIndex(index - 1)} aria-label="Back"><ArrowLeft /></Button>}
            <Button variant="primary" size="sm" onClick={() => (last ? onClose() : setIndex(index + 1))}>{last ? 'Done' : 'Next'}{!last && <ArrowRight />}</Button>
          </div>
        </div>
      </motion.div>
    </motion.div>
  )
}
