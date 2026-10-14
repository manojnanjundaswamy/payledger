import {
  Apple, ArrowDownLeft, ArrowLeftRight, ArrowUpRight, Baby, Banknote, Bike, BookOpen, Briefcase, Bus, Cake, Car, CircleDashed,
  Coffee, Coins, Cookie, CreditCard, Dumbbell, Film, Fuel, Gamepad2, Gift, GraduationCap, HandCoins, HandHelping, Heart,
  HeartPulse, Hotel, House, Landmark, Laptop, Music, PawPrint, Percent, PiggyBank, Pill, Pizza, Plane, Receipt, Repeat,
  Scissors, Shield, Shirt, ShoppingBag, ShoppingBasket, Smartphone, Sofa, Sparkles, Store, Tag, Ticket, TrainFront,
  TreePalm, TrendingUp, Trophy, Tv, Undo2, Utensils, Wallet, Wifi, Wrench, Zap, Pencil, type LucideIcon, } from 'lucide-react'
import { formatMoney } from '@/lib/money'
import { cn } from '@/lib/cn'
import { useProfile } from '@/state/profile'
import { categoryLabel, categoryTree, type AccountKind, type Category, type TxnKind } from '@/state/money'
import type { SearchOption } from '@/components/ui/search-select'

/**
 * Icons a category can have, by lucide name (what core-service stores). The first row are the
 * defaults' icons (V2 migration); anything unknown falls back to a dashed circle.
 */
export const CATEGORY_ICONS: Record<string, LucideIcon> = {
  'shopping-basket': ShoppingBasket, utensils: Utensils, house: House, zap: Zap, car: Car, 'shopping-bag': ShoppingBag,
  'heart-pulse': HeartPulse, 'graduation-cap': GraduationCap, repeat: Repeat, landmark: Landmark, shield: Shield,
  gift: Gift, plane: Plane, 'hand-helping': HandHelping, briefcase: Briefcase, store: Store, percent: Percent,
  'undo-2': Undo2, 'circle-dashed': CircleDashed,
  tag: Tag, coffee: Coffee, pizza: Pizza, cookie: Cookie, apple: Apple, cake: Cake, fuel: Fuel, bus: Bus,
  'train-front': TrainFront, bike: Bike, smartphone: Smartphone, wifi: Wifi, tv: Tv, laptop: Laptop, film: Film,
  music: Music, 'gamepad-2': Gamepad2, ticket: Ticket, 'book-open': BookOpen, shirt: Shirt, scissors: Scissors,
  sparkles: Sparkles, pill: Pill, dumbbell: Dumbbell, baby: Baby, 'paw-print': PawPrint, sofa: Sofa, wrench: Wrench,
  hotel: Hotel, 'tree-palm': TreePalm, heart: Heart, receipt: Receipt, 'hand-coins': HandCoins, coins: Coins,
  'piggy-bank': PiggyBank, 'trending-up': TrendingUp, trophy: Trophy, wallet: Wallet,
}

/** The validated chart palette (styles.css): a category's colour is also its colour in every chart. */
export const CATEGORY_COLORS = ['series-1', 'series-2', 'series-3', 'series-4', 'series-5', 'series-6', 'series-7', 'series-8'] as const

export const ACCOUNT_KINDS: Record<AccountKind, { label: string; icon: LucideIcon; hint: string }> = {
  BANK: { label: 'Bank account', icon: Landmark, hint: 'Savings or current account' },
  CARD: { label: 'Credit card', icon: CreditCard, hint: 'What you owe on it' },
  CASH: { label: 'Cash', icon: Banknote, hint: 'Money in your wallet' },
  WALLET: { label: 'Wallet', icon: Wallet, hint: 'Paytm, Amazon Pay, PhonePe…' },
}

export const TXN_KINDS: Record<TxnKind, { label: string; icon: LucideIcon }> = {
  EXPENSE: { label: 'Expense', icon: ArrowUpRight },
  INCOME: { label: 'Income', icon: ArrowDownLeft },
  TRANSFER: { label: 'Transfer', icon: ArrowLeftRight },
}

export function CategoryIcon({ category, className }: { category?: Category; className?: string }) {
  const Icon = (category && CATEGORY_ICONS[category.icon]) ?? CircleDashed
  return (
    <span
      className={cn('flex size-8 shrink-0 items-center justify-center rounded-full bg-surface-2', className)}
      style={category ? { color: `var(--${category.color})` } : undefined}
    >
      <Icon className="size-4" />
    </span>
  )
}

/** Formats paise in the user's locale (₹1,00,000.00 for en-IN). */
export function useMoneyFormat() {
  const locale = useProfile().data?.settings.locale ?? 'en-IN'
  return (minor: number, currency = 'INR', opts?: { sign?: boolean }) => formatMoney(minor, currency, locale, opts)
}

export function today(): string {
  // Local date, not UTC: at 1 a.m. in India the UTC date is still yesterday.
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** "Today", "Yesterday", or "Mon, 5 Oct" for a yyyy-mm-dd business date. */
export function dayLabel(isoDate: string, locale = 'en-IN'): string {
  if (isoDate === today()) return 'Today'
  const yesterday = new Date()
  yesterday.setDate(yesterday.getDate() - 1)
  const y = `${yesterday.getFullYear()}-${String(yesterday.getMonth() + 1).padStart(2, '0')}-${String(yesterday.getDate()).padStart(2, '0')}`
  if (isoDate === y) return 'Yesterday'
  const [year, month, day] = isoDate.split('-').map(Number)
  const date = new Date(year!, month! - 1, day!)
  return date.toLocaleDateString(locale, {
    weekday: 'short', day: 'numeric', month: 'short',
    year: year === new Date().getFullYear() ? undefined : 'numeric',
  })
}

/** The pencil on a row whose main click opens something else (its transactions). */
export function EditButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} aria-label={label} title="Edit"
      className="flex size-8 shrink-0 items-center justify-center rounded-control text-subtle hover:bg-surface-3 hover:text-fg focus-visible:text-fg">
      <Pencil className="size-4" />
    </button>
  )
}

export function Field({ label, htmlFor, hint, error, children }: {
  label: string; htmlFor: string; hint?: string; error?: string; children: React.ReactNode
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={htmlFor} className="text-sm font-medium">{label}</label>
      {children}
      {error ? <p className="text-xs text-critical" role="alert">{error}</p> : hint ? <p className="text-xs text-subtle">{hint}</p> : null}
    </div>
  )
}

/** Native select styled like Input: works with keyboards, screen readers and phone pickers for free. */
export function Select({ className, ...props }: React.SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select
      className={cn(
        'h-10 w-full rounded-control border border-border-strong bg-surface px-3 text-sm text-fg',
        'focus:border-accent focus:outline-none focus-visible:ring-2 focus-visible:ring-ring',
        className,
      )}
      {...props}
    />
  )
}

/**
 * Categories as options for a searchable dropdown: parents with their subcategories indented beneath, each with its
 * icon; a subcategory also matches its parent's name.
 */
export function categoryOptions(choices: Category[], all: Category[], opts: { group?: boolean } = {}): SearchOption[] {
  return categoryTree(choices).flatMap(({ category, children }) => [category, ...children]).map((c) => ({
    value: c.id,
    label: categoryLabel(c, all),
    icon: <CategoryIcon category={c} className="size-5 bg-transparent" />,
    indent: !!c.parentId && choices.some((p) => p.id === c.parentId),
    group: opts.group ? (c.kind === 'EXPENSE' ? 'Spending' : 'Income') : undefined,
  }))
}

/** Accounts as options, each with its kind's icon. */
export function accountOptions(accounts: { id: string; name: string; kind: AccountKind; archived?: boolean }[]): SearchOption[] {
  return accounts.map((a) => {
    const Icon = ACCOUNT_KINDS[a.kind].icon
    return { value: a.id, label: a.name + (a.archived ? ' (archived)' : ''), icon: <Icon className="text-muted" />, keywords: ACCOUNT_KINDS[a.kind].label }
  })
}
