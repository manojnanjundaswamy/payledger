import { Link } from '@tanstack/react-router'
import { motion } from 'motion/react'
import {
  ArrowLeftRight, Bell, CalendarClock, CalendarRange, Home, Import, Landmark, LayoutGrid, Link2, Lock, Milk, PieChart,
  Repeat, Scale, Search, ShieldCheck, Split, UserPlus, UserRoundPlus, Users, Wallet, type LucideIcon,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/card'

interface Feature { icon: LucideIcon; title: string; text: string }
interface PageInfo { icon: LucideIcon; title: string; lead: string; phase: string; features: Feature[] }

export const COMING: Record<string, PageInfo> = {
  transactions: {
    icon: ArrowLeftRight, phase: 'Phase 4', title: 'Every rupee, in one list',
    lead: 'Record spending in two taps, or import it all at once. Search, filter and fix categories in bulk.',
    features: [
      { icon: ArrowLeftRight, title: 'Quick add', text: 'Amount first, category second. Built for dozens of small UPI payments.' },
      { icon: Import, title: 'Import from a template', text: 'CSV or Excel with Indian dates and ₹1,00,000 amounts. Preview, fix, undo.' },
      { icon: Search, title: 'Search & saved views', text: 'Find anything, save the filters you use every week.' },
    ],
  },
  budgets: {
    icon: PieChart, phase: 'Phase 4', title: 'Budgets that warn you early',
    lead: 'Monthly limits per category that follow your payday, with a heads-up before you go over, not after.',
    features: [
      { icon: CalendarRange, title: 'Payday months', text: 'Your month can start on the 25th. Budgets follow it.' },
      { icon: Bell, title: 'Spending pace', text: '“At this rate you’ll spend ₹6,000 over on food” from the 5th of the month.' },
      { icon: Split, title: 'Only your share counts', text: 'A ₹3,000 dinner split three ways is ₹1,000 of your budget.' },
    ],
  },
  accounts: {
    icon: Wallet, phase: 'Phase 4', title: 'All your money, one view',
    lead: 'Bank accounts, cash, credit cards and wallets, with balances kept exact by a double-entry ledger.',
    features: [
      { icon: Landmark, title: 'Banks, cards, cash', text: 'Opening balances, transfers between your own accounts.' },
      { icon: Scale, title: 'Always balanced', text: 'Every transaction is a balanced ledger entry. Nothing drifts.' },
      { icon: ShieldCheck, title: 'Private by default', text: 'Personal accounts stay personal, even inside a household.' },
    ],
  },
  plan: {
    icon: CalendarClock, phase: 'Phase 4', title: 'No more surprise bills',
    lead: 'EMIs, card dues, SIPs, insurance and school fees on one calendar, counted before “safe to spend”.',
    features: [
      { icon: CalendarClock, title: 'Commitments calendar', text: 'Everything that is due, before it is due.' },
      { icon: Repeat, title: 'Annual planner', text: 'Diwali, insurance, fees: set aside a little every month.' },
      { icon: Milk, title: 'Daily services', text: 'Milk, newspaper, cook: mark missed days, the bill works itself out.' },
    ],
  },
  household: {
    icon: Home, phase: 'Phase 5', title: 'One budget for the whole family',
    lead: 'Add your spouse, children or parents. Plan together, see who paid what, keep private money private.',
    features: [
      { icon: Users, title: 'Members & dependents', text: 'Children and elderly parents without a login, still part of the plan.' },
      { icon: Lock, title: 'Shared or private', text: 'Choose per account. Share a summary without the details.' },
      { icon: Scale, title: 'Fair contributions', text: '50/50, by income or fixed amounts, tracked automatically.' },
    ],
  },
  groups: {
    icon: Users, phase: 'Phase 5', title: 'Split with anyone',
    lead: 'Trips, flatmates, office lunches. Invite by email or a link; friends join without creating an account.',
    features: [
      { icon: Link2, title: 'Join by link', text: 'Share on WhatsApp. Guests confirm their email and they are in.' },
      { icon: Split, title: 'Every kind of split', text: 'Equal, exact, percentages or shares, with several payers.' },
      { icon: Scale, title: 'Fewest payments', text: 'Simplify debts so everyone settles in as few transfers as possible.' },
    ],
  },
  people: {
    icon: UserRoundPlus, phase: 'Phase 5', title: 'Your people',
    lead: 'Everyone you share money with, in one place: balances per person, shared groups, and who you have invited.',
    features: [
      { icon: UserPlus, title: 'Connections', text: 'Invite by email; they can join from a link without signing up.' },
      { icon: Scale, title: 'Balance per person', text: 'Across every group and household you share.' },
      { icon: ShieldCheck, title: 'You decide what they see', text: 'Only shared groups and summaries, never your private accounts.' },
    ],
  },
}

export function ComingSoon({ page }: { page: keyof typeof COMING }) {
  const info = COMING[page]!
  return (
    <div className="mx-auto flex max-w-4xl flex-col items-center gap-10 py-8 text-center sm:py-14">
      <div className="flex flex-col items-center gap-4">
        <motion.div initial={{ scale: 0.8, rotate: -6, opacity: 0 }} animate={{ scale: 1, rotate: 0, opacity: 1 }} transition={{ type: 'spring', stiffness: 260, damping: 18 }}
          className="flex size-16 items-center justify-center rounded-[calc(var(--radius)+4px)] bg-accent text-accent-fg shadow-lift">
          <info.icon className="size-7" />
        </motion.div>
        <Badge tone="accent">Coming in {info.phase}</Badge>
        <h2 className="max-w-xl text-3xl font-semibold tracking-tight text-balance sm:text-4xl">{info.title}</h2>
        <p className="max-w-xl text-muted text-balance">{info.lead}</p>
      </div>
      <div className="grid w-full gap-3 text-left sm:grid-cols-3">
        {info.features.map((f, i) => (
          <motion.div key={f.title} initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.08 * i + 0.1 }}
            className="rounded-card border border-border bg-surface p-pad shadow-card">
            <f.icon className="size-5 text-muted" />
            <div className="mt-3 font-medium">{f.title}</div>
            <p className="mt-1 text-sm text-muted">{f.text}</p>
          </motion.div>
        ))}
      </div>
      <Button asChild variant="outline"><Link to="/dashboard"><LayoutGrid />Meanwhile, design your dashboard</Link></Button>
    </div>
  )
}
