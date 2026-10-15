import { useMemo, useState, type FormEvent } from 'react'
import { MoneyInput } from '@/components/ui/money-input'
import { ChevronLeft, ChevronRight, Lock, MoreHorizontal, Pencil, Plus, Trash2, Undo2, Wallet } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card, Skeleton } from '@/components/ui/card'
import { Input, Segmented } from '@/components/ui/controls'
import { Dialog, Menu, MenuContent, MenuItem, MenuTrigger } from '@/components/ui/overlay'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/cn'
import { formatMoney, formatMoneyWhole, minorToInput, parseMoney } from '@/lib/money'
import { useAccounts, useCategories } from '@/state/money'
import {
  useDailyServices, useMarkDay, usePayService, useRemoveService, useSaveService, useUnpayService,
  type DailyService, type ServiceRow,
} from '@/state/dailyServices'
import { AccountPicker, CategoryPicker } from './pickers'
import { Field, accountOptions, categoryOptions, today } from './parts'

/** Common services with usual Indian rates; every number is editable. */
const PRESETS: { name: string; emoji: string; per: 'DAY' | 'MONTH'; rate: string; paidLeaves?: number }[] = [
  { name: 'Milk', emoji: '🥛', per: 'DAY', rate: '60' },
  { name: 'Newspaper', emoji: '📰', per: 'DAY', rate: '8' },
  { name: 'Water can', emoji: '💧', per: 'DAY', rate: '40' },
  { name: 'Maid', emoji: '🧹', per: 'MONTH', rate: '4,000', paidLeaves: 2 },
  { name: 'Cook', emoji: '🍳', per: 'MONTH', rate: '8,000', paidLeaves: 2 },
  { name: 'Driver', emoji: '🚗', per: 'MONTH', rate: '15,000', paidLeaves: 4 },
]

const monthKey = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
const shift = (month: string, by: number) => { const [y, m] = month.split('-').map(Number); return monthKey(new Date(y!, m! - 1 + by, 1)) }
const monthName = (month: string) => { const [y, m] = month.split('-').map(Number); return new Date(y!, m! - 1, 1).toLocaleDateString('en-IN', { month: 'long', year: 'numeric' }) }

/**
 * Milk, newspaper, a cook, a maid: tap the days that were missed (or doubled), see the month's bill, and pay it as one
 * expense. Product plan 2.1, "Daily services".
 */
export function DailyServicesPage() {
  const [month, setMonth] = useState(() => monthKey(new Date()))
  const { data: rows, isPending } = useDailyServices(month)
  const [editing, setEditing] = useState<{ service?: DailyService; preset?: (typeof PRESETS)[number] } | null>(null)
  const total = (rows ?? []).reduce((t, r) => t + r.bill.amount, 0)

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-3">
        <div className="min-w-0 flex-1">
          <h2 className="text-2xl font-semibold tracking-tight">Daily services</h2>
          <p className="text-sm text-muted">Milk, newspaper, the cook or the maid: tap a day they didn’t come, and the bill works itself out.</p>
        </div>
        <Button variant="primary" onClick={() => setEditing({})}><Plus />Add a service</Button>
      </div>

      <div className="flex items-center gap-2">
        <Button variant="ghost" size="icon" aria-label="Previous month" onClick={() => setMonth(shift(month, -1))}><ChevronLeft /></Button>
        <span className="min-w-36 text-center font-medium" data-testid="services-month">{monthName(month)}</span>
        <Button variant="ghost" size="icon" aria-label="Next month" onClick={() => setMonth(shift(month, 1))}><ChevronRight /></Button>
        {total > 0 && <span className="ml-auto text-sm text-muted">This month: <b className="text-fg tabular">{formatMoneyWhole(total)}</b></span>}
      </div>

      {isPending ? <Skeleton className="h-64" /> : !rows || rows.length === 0 ? (
        <Card className="flex flex-col items-center gap-4 px-pad py-10 text-center">
          <span className="text-4xl" aria-hidden>🥛📰🧹</span>
          <div>
            <p className="font-medium">No services yet</p>
            <p className="text-sm text-muted">Start with one of these; you can change the rate.</p>
          </div>
          <div className="flex flex-wrap justify-center gap-2">
            {PRESETS.map((p) => (
              <Button key={p.name} variant="outline" onClick={() => setEditing({ preset: p })}><span aria-hidden>{p.emoji}</span>{p.name}</Button>
            ))}
          </div>
        </Card>
      ) : (
        <div className="grid gap-4 lg:grid-cols-2">
          {rows.map((r) => <ServiceCard key={r.service.id} row={r} month={month} onEdit={() => setEditing({ service: r.service })} />)}
        </div>
      )}
      <ServiceDialog open={editing !== null} onOpenChange={(o) => !o && setEditing(null)} service={editing?.service} preset={editing?.preset} />
    </div>
  )
}

function ServiceCard({ row, month, onEdit }: { row: ServiceRow; month: string; onEdit: () => void }) {
  const { service: s, bill } = row
  const mark = useMarkDay()
  const pay = usePayService()
  const unpay = useUnpayService()
  const remove = useRemoveService()
  const [paying, setPaying] = useState(false)
  const [y, m] = month.split('-').map(Number)
  const days = new Date(y!, m!, 0).getDate()
  // Monday-first grid: blanks before the 1st.
  const lead = (new Date(y!, m! - 1, 1).getDay() + 6) % 7
  const now = today()
  const locked = !!bill.paid

  async function cycle(day: string) {
    if (locked) return
    const q = bill.marks[day] ?? 1
    // Day services: normal → missed → double → triple → normal. Monthly: worked ↔ off.
    const next = s.per === 'MONTH' ? (q === 0 ? 1 : 0) : q === 1 ? 0 : q === 0 ? 2 : q >= 3 ? 1 : q + 1
    try {
      await mark.mutateAsync({ id: s.id, day, quantity: next })
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Could not change that day')
    }
  }

  return (
    <Card className="flex flex-col gap-3 p-4" data-testid={`service-${s.name}`}>
      <div className="flex items-start gap-3">
        <span className="flex size-11 shrink-0 items-center justify-center rounded-full bg-surface-2 text-2xl" aria-hidden>{s.emoji ?? '🧾'}</span>
        <div className="min-w-0 flex-1">
          <p className="truncate font-medium">{s.name}</p>
          <p className="text-xs text-muted">{formatMoney(s.rate)} {s.per === 'DAY' ? 'a day' : 'a month'}
            {s.per === 'MONTH' && s.paidLeaves > 0 && ` · ${s.paidLeaves} paid ${s.paidLeaves === 1 ? 'leave' : 'leaves'}`}
            {s.endedOn && ` · stopped ${s.endedOn}`}</p>
        </div>
        <Menu>
          <MenuTrigger asChild><Button variant="ghost" size="icon-sm" aria-label={`More for ${s.name}`}><MoreHorizontal /></Button></MenuTrigger>
          <MenuContent>
            <MenuItem icon={<Pencil />} onSelect={onEdit}>Edit</MenuItem>
            <MenuItem icon={<Trash2 />} destructive onSelect={() => void remove.mutateAsync(s.id).then(() => toast.success(`${s.name} removed`))}>Remove</MenuItem>
          </MenuContent>
        </Menu>
      </div>

      <div className="grid grid-cols-7 gap-1 text-center text-[0.625rem] text-subtle" aria-hidden>
        {['M', 'T', 'W', 'T', 'F', 'S', 'S'].map((d, i) => <span key={i}>{d}</span>)}
      </div>
      <div className="-mt-2 grid grid-cols-7 gap-1" role="group" aria-label={`Days of ${monthName(month)}`}>
        {Array.from({ length: lead }, (_, i) => <span key={`b${i}`} />)}
        {Array.from({ length: days }, (_, i) => {
          const d = `${month}-${String(i + 1).padStart(2, '0')}`
          const outside = d < s.startedOn || (!!s.endedOn && d > s.endedOn)
          const q = bill.marks[d] ?? 1
          const future = d > now
          return (
            <button key={d} type="button" disabled={outside || locked || mark.isPending} onClick={() => void cycle(d)}
              aria-label={`${i + 1}: ${outside ? 'not running' : q === 0 ? (s.per === 'MONTH' ? 'off' : 'missed') : q > 1 ? `${q} times` : 'normal'}`}
              className={cn('relative flex aspect-square items-center justify-center rounded-md text-xs tabular transition-colors',
                outside ? 'text-subtle/50' : q === 0 ? 'border border-critical/60 bg-critical/10 text-critical line-through'
                  : q > 1 ? 'bg-accent text-accent-fg font-semibold' : future ? 'bg-surface-2/50 text-muted' : 'bg-good/15 text-fg',
                !outside && !locked && 'hover:ring-2 hover:ring-ring', d === now && 'ring-2 ring-accent')}>
              {i + 1}
              {q > 1 && <span className="absolute -top-1 -right-1 rounded-full bg-fg px-1 text-[0.5625rem] leading-3.5 text-bg">{q}×</span>}
            </button>
          )
        })}
      </div>
      <p className="text-[0.6875rem] text-subtle">{locked ? 'Paid: the days are fixed.' : s.per === 'DAY' ? 'Tap a day: missed, then double, triple, back to normal.' : 'Tap a day off; it comes off the pay beyond the paid leaves.'}</p>

      <div className="flex flex-wrap items-center gap-2 border-t border-border pt-3">
        <div className="min-w-0 flex-1">
          <p className="text-lg font-semibold tabular" data-testid="service-bill">{formatMoney(bill.amount)}</p>
          <p className="text-xs text-muted">
            {s.per === 'DAY' ? `${bill.units} ${bill.units === 1 ? 'delivery' : 'deliveries'}` : `${bill.days - bill.missed} days worked`}
            {bill.missed > 0 && ` · ${bill.missed} ${s.per === 'DAY' ? 'missed' : 'off'}`}
            {bill.extra > 0 && ` · ${bill.extra} extra`}
            {!locked && bill.soFar !== bill.amount && ` · ${formatMoney(bill.soFar)} so far`}
          </p>
        </div>
        {locked ? (
          <>
            <span className="flex items-center gap-1 text-sm text-good-text"><Lock className="size-3.5" />Paid</span>
            <Button variant="ghost" size="sm" onClick={() => void unpay.mutateAsync({ id: s.id, month }).then(() => toast('Payment undone; its expense is deleted'))}><Undo2 />Undo</Button>
          </>
        ) : bill.amount > 0 && (
          <Button variant="primary" size="sm" onClick={() => setPaying(true)}><Wallet />Pay {formatMoneyWhole(bill.amount)}</Button>
        )}
      </div>
      <PayDialog open={paying} onOpenChange={setPaying} row={row} month={month} onPay={async (accountId) => {
        await pay.mutateAsync({ id: s.id, month, accountId })
        toast.success(`${s.name} paid: ${formatMoney(bill.amount)} recorded as an expense`)
      }} />
    </Card>
  )
}

function PayDialog({ open, onOpenChange, row, month, onPay }: {
  open: boolean; onOpenChange: (o: boolean) => void; row: ServiceRow; month: string; onPay: (accountId: string) => Promise<void>
}) {
  const { data: accounts } = useAccounts()
  const payable = (accounts ?? []).filter((a) => !a.archived && a.kind !== 'OWED')
  const [account, setAccount] = useState(row.service.accountId ?? '')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!account) return setError('Choose the account you paid from.')
    setBusy(true)
    try {
      await onPay(account)
      onOpenChange(false)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not record it.')
    } finally {
      setBusy(false)
    }
  }
  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={`Pay ${row.service.name}`} description={`${monthName(month)}: ${formatMoney(row.bill.amount)}, recorded as one expense.`}>
      <form onSubmit={submit} className="flex flex-col gap-4 p-pad">
        <Field label="Paid from" htmlFor="pay-account">
          <AccountPicker id="pay-account" value={account} onChange={setAccount} options={accountOptions(payable)} placeholder="Which account?" />
        </Field>
        {error && <p className="text-sm text-critical" role="alert">{error}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" type="button" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button variant="primary" type="submit" disabled={busy}>{busy ? 'Recording…' : 'Record payment'}</Button>
        </div>
      </form>
    </Dialog>
  )
}

function ServiceDialog({ open, onOpenChange, service, preset }: {
  open: boolean; onOpenChange: (o: boolean) => void; service?: DailyService; preset?: (typeof PRESETS)[number]
}) {
  // Remounted per service or preset, so the form starts from it.
  return open ? <ServiceForm key={service?.id ?? preset?.name ?? 'new'} onOpenChange={onOpenChange} service={service} preset={preset} /> : null
}

function ServiceForm({ onOpenChange, service, preset }: { onOpenChange: (o: boolean) => void; service?: DailyService; preset?: (typeof PRESETS)[number] }) {
  const save = useSaveService()
  const { data: accounts } = useAccounts()
  const { data: categories } = useCategories()
  const expense = useMemo(() => (categories ?? []).filter((c) => c.kind === 'EXPENSE' && !c.hidden), [categories])
  const [name, setName] = useState(service?.name ?? preset?.name ?? '')
  const [emoji, setEmoji] = useState(service?.emoji ?? preset?.emoji ?? '')
  const [per, setPer] = useState<'DAY' | 'MONTH'>(service?.per ?? preset?.per ?? 'DAY')
  const [rate, setRate] = useState(service ? minorToInput(service.rate) : preset?.rate ?? '')
  const [leaves, setLeaves] = useState(String(service?.paidLeaves ?? preset?.paidLeaves ?? 0))
  const [account, setAccount] = useState(service?.accountId ?? '')
  const [category, setCategory] = useState(service?.categoryId ?? '')
  const [start, setStart] = useState(service?.startedOn ?? `${monthKey(new Date())}-01`)
  const [error, setError] = useState<string>()

  async function submit(e: FormEvent) {
    e.preventDefault()
    const paise = parseMoney(rate)
    if (!name.trim()) return setError('Name it: Milk, Maid…')
    if (!paise || paise <= 0) return setError('Enter the rate.')
    try {
      await save.mutateAsync({ id: service?.id, fields: {
        name: name.trim(), emoji: emoji || null, per, rate: paise, paidLeaves: per === 'MONTH' ? Number(leaves) || 0 : 0,
        accountId: account || null, categoryId: category || null, startedOn: start,
      } })
      toast.success(service ? 'Saved' : `${name.trim()} added`)
      onOpenChange(false)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not save it.')
    }
  }
  return (
    <Dialog open onOpenChange={onOpenChange} title={service ? `Edit ${service.name}` : 'Add a service'}
      description="A rate per day for deliveries (milk, paper), or per month for a salary (cook, maid).">
      <form onSubmit={submit} className="flex flex-col gap-4 p-pad">
        <div className="grid grid-cols-[4.5rem_1fr] gap-3">
          <Field label="Icon" htmlFor="svc-emoji"><Input id="svc-emoji" value={emoji} onChange={(e) => setEmoji(e.target.value)} maxLength={4} className="text-center text-lg" /></Field>
          <Field label="Name" htmlFor="svc-name"><Input id="svc-name" value={name} onChange={(e) => setName(e.target.value)} maxLength={60} placeholder="Milk" /></Field>
        </div>
        <Segmented label="Rate" value={per} onChange={setPer} className="self-start" options={[{ value: 'DAY', label: 'Per day' }, { value: 'MONTH', label: 'Per month' }]} />
        <div className="grid grid-cols-2 gap-3">
          <Field label={per === 'DAY' ? 'A normal day costs (₹)' : 'Monthly pay (₹)'} htmlFor="svc-rate">
            <MoneyInput id="svc-rate" value={rate} onChange={(e) => setRate(e.target.value)} />
          </Field>
          {per === 'MONTH' ? (
            <Field label="Paid leaves a month" htmlFor="svc-leaves"><Input id="svc-leaves" value={leaves} onChange={(e) => setLeaves(e.target.value.replace(/\D/g, ''))} inputMode="numeric" /></Field>
          ) : (
            <Field label="Started" htmlFor="svc-start"><Input id="svc-start" type="date" value={start} onChange={(e) => setStart(e.target.value)} /></Field>
          )}
        </div>
        <Field label="Usually paid from" htmlFor="svc-account" hint="Optional; you can choose when paying.">
          <AccountPicker id="svc-account" value={account} onChange={setAccount} options={accountOptions((accounts ?? []).filter((a) => !a.archived && a.kind !== 'OWED'))} placeholder="Any account" />
        </Field>
        <Field label="Category" htmlFor="svc-category" hint="Else Household help.">
          <CategoryPicker kind="EXPENSE" id="svc-category" value={category} onChange={setCategory} options={categoryOptions(expense, categories ?? [])} placeholder="Household help" />
        </Field>
        {error && <p className="text-sm text-critical" role="alert">{error}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" type="button" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button variant="primary" type="submit" disabled={save.isPending}>{save.isPending ? 'Saving…' : service ? 'Save' : 'Add service'}</Button>
        </div>
      </form>
    </Dialog>
  )
}
