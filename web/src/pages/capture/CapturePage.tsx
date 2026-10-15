import { useEffect, useMemo, useRef, useState } from 'react'
import { MoneyInput } from '@/components/ui/money-input'
import { AccountPicker, CategoryPicker } from '@/pages/money/pickers'
import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { AlertTriangle, ArrowDownLeft, ArrowUpRight, Check, CheckCircle2, ChevronDown, ChevronUp, Copy, ExternalLink, FileUp, Inbox, Loader2, Mail, RefreshCw, Settings2, ShieldCheck, Sparkles, X } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Badge, Card, Skeleton } from '@/components/ui/card'
import { Input, Segmented, Switch } from '@/components/ui/controls'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/cn'
import { formatMoneyWhole, minorToInput, parseMoney } from '@/lib/money'
import { accountOptions, categoryOptions, dayLabel, today, useMoneyFormat } from '@/pages/money/parts'
import { BANK_SENDERS, bankFilter, useAutoAdd, useUndoCaptured, type CaptureView, type CapturedItem, type ReceivedEmail, useAddAll, useAddCaptured, useCapture, useDismissCaptured, useForwardingConfirmed, usePaste, useResetAddress, useStatement } from '@/state/capture'
import { useAccounts, useCategories, usePersonalSpaceId } from '@/state/money'
import { ForwardFlow, GmailSettings, MethodIcon, PhoneSms } from './illustrations'

type Method = 'email' | 'paste' | 'share' | 'statement'

/**
 * Auto-capture: bank alerts come in without typing (forwarded emails, pasted or shared SMS) and wait here to be
 * checked. Nothing reaches your accounts until you add it. The guide shows each way in, step by step.
 */
export function CapturePage() {
  const search = useSearch({ from: '/app/capture' })
  const navigate = useNavigate()
  const [method, setMethod] = useState<Method>(search.method ?? 'email')
  const waiting = useRef(false)
  const { data, isPending } = useCapture(waiting.current)
  const paste = usePaste()
  const shared = useRef(false)

  // Opened from Android's share sheet ("Share → PayLedger"): read what was shared at once.
  useEffect(() => {
    const text = [search.title, search.text].filter(Boolean).join('\n').trim()
    if (!text || shared.current) return
    shared.current = true
    paste.mutate({ text, source: 'SHARE' }, {
      onSuccess: (r) => toast.success(r.added ? `${r.added} ${r.added === 1 ? 'entry' : 'entries'} to review` : r.duplicates ? 'Already here' : 'Nothing to add',
        { description: r.added ? 'Check it below, then Add.' : r.duplicates ? 'You shared this one before.' : "That doesn't look like a bank transaction." }),
      onError: (e) => toast.error("Couldn't read it", { description: e.message }),
    })
    void navigate({ to: '/capture', search: {}, replace: true })
  }, [search.text, search.title]) // eslint-disable-line react-hooks/exhaustive-deps

  // Poll while the guide waits for Gmail's code or the first email.
  waiting.current = !!data && data.emailReady && !data.lastEmailAt

  const pending = (data?.items ?? []).filter((i) => i.status === 'PENDING')
  const unreadable = (data?.items ?? []).filter((i) => i.status === 'UNREADABLE')
  const decided = (data?.items ?? []).filter((i) => i.status === 'ADDED' || i.status === 'DISMISSED')

  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div className="max-w-xl">
          <p className="text-sm text-muted">
            Bank alerts in, typing out. Forward your bank's emails or paste its SMS: we read the amount, the date and who it was with,
            and guess the account and category. <span className="font-medium text-fg">Nothing is added until you check it.</span>
          </p>
        </div>
        {data && <Badge tone={data.pending ? 'warning' : 'good'}>{data.pending ? `${data.pending} to review` : 'All reviewed'}</Badge>}
      </header>

      {isPending && <Skeleton className="h-48" />}

      {pending.length > 0 && <ReviewList items={pending} />}
      {unreadable.length > 0 && (
        <section className="flex flex-col gap-3" aria-label="Couldn't read">
          <h2 className="text-base font-semibold">Couldn't read {unreadable.length === 1 ? 'this one' : 'these'}</h2>
          <p className="-mt-2 text-sm text-muted">Fill one in once: alerts like it are read by themselves after that.</p>
          {unreadable.map((i) => <ReviewCard key={i.id} item={i} />)}
        </section>
      )}

      <section className="flex flex-col gap-3" aria-label="Ways to bring entries in">
        <h2 className="text-base font-semibold">{pending.length ? 'Bring more in' : 'Set it up once'}</h2>
        <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4" role="tablist">
          {([
            { key: 'paste', title: 'Paste SMS', text: 'Copy one or many bank SMS and paste them here.', tag: 'Works for every bank' },
            { key: 'email', title: 'Forward bank emails', text: 'Automatic once set up, for banks whose emails can be forwarded.' },
            { key: 'share', title: 'Share from Messages', text: 'Android: Share → PayLedger, after installing the app.' },
            { key: 'statement', title: 'Bank statement', text: 'Months of history at once: CSV, Excel or PDF from net banking.' },
          ] as const).map((m) => (
            <button key={m.key} type="button" role="tab" aria-selected={method === m.key} onClick={() => setMethod(m.key)}
              className={cn('flex items-start gap-3 rounded-card border p-3 text-left transition-colors',
                method === m.key ? 'border-accent bg-accent-soft/40' : 'border-border bg-surface hover:bg-surface-2')}>
              <MethodIcon method={m.key} />
              <span className="min-w-0">
                <span className="flex flex-wrap items-center gap-1.5 text-sm font-medium">{m.title}{'tag' in m && <Badge tone="good">{m.tag}</Badge>}</span>
                <span className="block text-xs text-muted">{m.text}</span>
              </span>
            </button>
          ))}
        </div>
        {data && method === 'email' && (
          <>
            <p className="flex items-start gap-2 rounded-control bg-warning/10 px-3 py-2 text-xs">
              <AlertTriangle className="mt-0.5 size-3.5 shrink-0 text-warning" />
              <span>Some banks' emails (HDFC's, for one) are refused when Gmail forwards them, because of how the bank signs them. Gmail then
                emails you "Message blocked". For those banks, paste or share the SMS instead.</span>
            </p>
            <EmailGuide data={data} />
          </>
        )}
        {method === 'paste' && <PasteBox />}
        {method === 'share' && <ShareGuide />}
        {method === 'statement' && <StatementBox />}
      </section>

      {data && <AutoAddSetting on={data.autoAdd} />}

      {decided.length > 0 && (
        <section className="flex flex-col gap-2" aria-label="Recently decided">
          <h2 className="text-sm font-medium text-muted">Recently</h2>
          <Card className="divide-y divide-border">
            {decided.map((i) => (
              <div key={i.id} className="flex items-center gap-3 px-pad py-2 text-sm">
                {i.status === 'ADDED' ? <Check className="size-4 text-good-text" /> : <X className="size-4 text-subtle" />}
                <span className="min-w-0 flex-1 truncate">{i.merchant ?? i.bank ?? 'Bank alert'}</span>
                {i.amount !== null && <span className={cn('tabular text-xs', i.kind === 'INCOME' ? 'text-[var(--flow-in)]' : 'text-muted')}>{i.kind === 'INCOME' ? '+' : '−'}{formatMoneyWhole(i.amount)}</span>}
                {i.autoAdded && <Badge tone="accent" title={i.confidence != null ? `${Math.round(i.confidence * 100)}% sure` : undefined}>On its own</Badge>}
                {i.status === 'ADDED' && i.transactionId
                  ? <>
                      <Link to="/transactions" search={{ open: i.transactionId }} className="text-xs underline underline-offset-4">Open</Link>
                      <UndoButton id={i.id} />
                    </>
                  : <span className="text-xs text-subtle">dismissed</span>}
              </div>
            ))}
          </Card>
        </section>
      )}

      <p className="flex items-start gap-2 text-xs text-subtle">
        <ShieldCheck className="mt-0.5 size-3.5 shrink-0" />
        We never read your mailbox or your phone: only what you forward or paste reaches us. The original text is deleted once you add or
        dismiss an entry, and everything goes with your account if you delete it.
      </p>
    </div>
  )
}

/** Sure ones in without a tap: on by default; off means everything waits here. */
function AutoAddSetting({ on }: { on: boolean }) {
  const set = useAutoAdd()
  return (
    <Card className="flex items-start justify-between gap-4 p-pad">
      <div className="min-w-0">
        <p className="text-sm font-medium">Add on their own when we're sure</p>
        <p className="text-xs text-muted">An alert we're at least 95% sure of (amount, date, payee and your account) is added without asking. You'll get a
          notification, and Undo is here if it's wrong.</p>
      </div>
      <Switch checked={on} label="Add on their own when we're sure" onCheckedChange={(v) => set.mutate(v)} />
    </Card>
  )
}

function UndoButton({ id }: { id: string }) {
  const undo = useUndoCaptured()
  return (
    <button type="button" className="text-xs text-muted underline underline-offset-4 hover:text-fg disabled:opacity-50" disabled={undo.isPending}
      onClick={() => undo.mutate(id, {
        onSuccess: () => toast('Taken back', { description: 'It waits in To review again.' }),
        onError: () => toast.error("Couldn't undo it", { description: 'Open it in Transactions to change or delete it.' }),
      })}>
      Undo
    </button>
  )
}

// ---------------------------------------------------------------- review

function ReviewList({ items }: { items: CapturedItem[] }) {
  const addAll = useAddAll()
  const [shown, setShown] = useState(25)
  const ready = items.filter((i) => i.accountId && !i.duplicateOf)
  const busy = addAll.isPending

  async function addReady() {
    const r = await addAll.mutateAsync(ready.map((i) => i.id))
    toast.success(`${r.added} added`, { description: r.skipped ? `${r.skipped} need a closer look.` : 'All in your accounts now.' })
  }

  return (
    <section className="flex flex-col gap-3" aria-label="To review">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="flex items-center gap-2 text-base font-semibold"><Inbox className="size-5 text-accent" />To review</h2>
        {ready.length > 1 && (
          <Button size="sm" variant="primary" onClick={() => void addReady()} disabled={busy}>
            {busy ? <Loader2 className="animate-spin" /> : <Check />}Add all {ready.length} that look right
          </Button>
        )}
      </div>
      <div className="flex flex-col gap-3">
        {items.slice(0, shown).map((i) => <ReviewCard key={i.id} item={i} />)}
      </div>
      {items.length > shown && (
        <Button variant="ghost" className="self-center" onClick={() => setShown(shown + 25)}>Show {Math.min(25, items.length - shown)} more of {items.length - shown}</Button>
      )}
    </section>
  )
}

function ReviewCard({ item }: { item: CapturedItem }) {
  const personalSpace = usePersonalSpaceId()
  const { data: accounts } = useAccounts(false, personalSpace)
  const { data: categories } = useCategories(personalSpace)
  const add = useAddCaptured()
  const dismiss = useDismissCaptured()
  const money = useMoneyFormat()
  const [kind, setKind] = useState<'EXPENSE' | 'INCOME'>(item.kind ?? 'EXPENSE')
  const [description, setDescription] = useState(item.merchant ?? item.bank ?? '')
  const [amount, setAmount] = useState(item.amount ? minorToInput(item.amount) : '')
  const [date, setDate] = useState(item.occurredOn ?? today())
  const [account, setAccount] = useState(item.accountId ?? '')
  const [category, setCategory] = useState(item.categoryId ?? '')
  // "NEHA SHARMA sent you 1,500": the sender is who it was with.
  const [person, setPerson] = useState(item.kind === 'INCOME' ? item.merchant ?? '' : '')
  // An unread message shows its text at once: it's what you fill the form from.
  const [open, setOpen] = useState(item.status === 'UNREADABLE')
  const [error, setError] = useState<string>()
  const payable = (accounts ?? []).filter((a) => a.kind !== 'OWED' && !a.archived)
  const choices = useMemo(() => (categories ?? []).filter((c) => c.kind === kind && !c.hidden), [categories, kind])
  const out = kind === 'EXPENSE'

  async function submit() {
    setError(undefined)
    const value = parseMoney(amount)
    if (value === null || value <= 0) return setError('Check the amount.')
    if (!account) return setError('Which account was it?')
    try {
      const saved = await add.mutateAsync({ id: item.id, kind, amount: value, accountId: account, categoryId: category || null, occurredOn: date,
        description: description.trim() || (out ? 'Spent' : 'Received'), counterparty: person.trim() || null })
      toast.success(`${description || 'Entry'} added`, {
        description: saved.learned === 'pattern' ? 'Learned: alerts like this one are read by themselves from now on.'
          : saved.learned === 'name' ? `Learned: it'll be called ${description.trim()} next time.` : undefined,
      })
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not add it. Try again.')
    }
  }

  return (
    <Card className="flex flex-col gap-3 p-pad" data-testid={`review-${item.merchant ?? item.id}`}>
      <div className="flex items-start gap-3">
        <span className={cn('flex size-10 shrink-0 items-center justify-center rounded-full', out ? 'bg-[var(--flow-out)]/10 text-[var(--flow-out)]' : 'bg-[var(--flow-in)]/10 text-[var(--flow-in)]')}>
          {out ? <ArrowUpRight className="size-5" /> : <ArrowDownLeft className="size-5" />}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate font-medium">{item.merchant ?? (item.status === 'UNREADABLE' ? 'A message to fill in' : 'Someone')}</span>
          <span className="block truncate text-xs text-subtle">
            {[item.bank, item.accountHint && `••${item.accountHint}`, item.occurredOn && dayLabel(item.occurredOn),
              item.source === 'EMAIL' ? 'email' : item.source === 'STATEMENT' ? 'statement' : 'SMS'].filter(Boolean).join(' · ')}
          </span>
        </span>
        <span className={cn('shrink-0 text-lg font-semibold tabular', out ? 'text-[var(--flow-out)]' : 'text-[var(--flow-in)]')}>
          {item.amount === null ? '' : `${out ? '−' : '+'}${money(item.amount)}`}
        </span>
      </div>

      {item.status === 'UNREADABLE' && (
        <p className="flex items-start gap-2 rounded-control bg-surface-2 px-3 py-2 text-xs text-muted">
          <Sparkles className="mt-0.5 size-3.5 shrink-0 text-accent" />
          <span><b className="text-fg">Teach it this one.</b> Fill in the amount and who it was with; next time an alert like it comes, it's read for you.</span>
        </p>
      )}
      {item.readBy === 'LEARNED' && (
        <p className="flex items-center gap-1.5 text-xs text-subtle"><Sparkles className="size-3.5 text-accent" />Read with what you taught it.</p>
      )}
      {item.readBy === 'AI' && (
        <p className="flex items-center gap-1.5 text-xs text-subtle"><Sparkles className="size-3.5 text-accent" />
          Read by AI{item.confidence != null ? ` · ${Math.round(item.confidence * 100)}% sure` : ''} · account numbers were hidden first</p>
      )}
      {item.duplicateOf && (
        <p className="flex flex-wrap items-center gap-2 rounded-control bg-warning/10 px-3 py-2 text-xs">
          <AlertTriangle className="size-3.5 shrink-0 text-warning" />
          <span className="min-w-0 flex-1">Looks like you entered this already.</span>
          <Link to="/transactions" search={{ open: item.duplicateOf }} className="underline underline-offset-4">See it</Link>
        </p>
      )}

      <div className="grid gap-2 sm:grid-cols-2">
        <Segmented label="Spent or received" value={kind} onChange={(k) => { setKind(k); setCategory('') }} className="w-full"
          options={[{ value: 'EXPENSE', label: 'Spent' }, { value: 'INCOME', label: 'Received' }]} />
        <Input aria-label="Description" value={description} onChange={(e) => setDescription(e.target.value)} maxLength={120} placeholder="Who or what" />
        <AccountPicker space={personalSpace} aria-label="Account" value={account} onChange={setAccount} options={accountOptions(payable)} placeholder="Which account?" />
        <CategoryPicker space={personalSpace} kind={kind} aria-label="Category" value={category} onChange={setCategory} options={categoryOptions(choices, categories ?? [])}
          placeholder="Category (else Other)" />
        <MoneyInput aria-label="Amount" className="tabular" value={amount} onChange={(e) => setAmount(e.target.value)} />
        <Input aria-label="Date" type="date" value={date} onChange={(e) => setDate(e.target.value)} />
        <Input aria-label={out ? 'Paid to' : 'From whom'} value={person} onChange={(e) => setPerson(e.target.value)} maxLength={80}
          placeholder={out ? 'Paid to (optional)' : 'From whom (optional)'} className="sm:col-span-2" />
      </div>
      {item.categorySource && category === item.categoryId && (
        <p className="flex items-center gap-1.5 text-xs text-subtle"><Sparkles className="size-3.5" />Category guessed from {item.categorySource === 'RULE' ? 'your rule' : 'what you chose before'}.</p>
      )}
      {error && <p className="rounded-control bg-critical/10 px-3 py-2 text-sm text-critical" role="alert">{error}</p>}

      <div className="flex flex-wrap items-center gap-2">
        {item.body && (
          <button type="button" onClick={() => setOpen(!open)} className="flex items-center gap-1 text-xs text-muted hover:text-fg" aria-expanded={open}>
            <ChevronDown className={cn('size-3.5 transition-transform', open && 'rotate-180')} />The original
          </button>
        )}
        <span className="ml-auto flex gap-2">
          <Button size="sm" variant="ghost" onClick={() => dismiss.mutate(item.id)} disabled={dismiss.isPending}>Dismiss</Button>
          <Button size="sm" variant="primary" onClick={() => void submit()} disabled={add.isPending}><Check />Add</Button>
        </span>
      </div>
      {open && item.body && <pre className="max-h-48 overflow-auto whitespace-pre-wrap rounded-control bg-surface-2 p-3 text-xs text-muted">{item.subject ? `${item.subject}\n\n` : ''}{item.body}</pre>}
    </Card>
  )
}

// ---------------------------------------------------------------- guides

function Step({ n, title, done, children }: { n: number; title: string; done?: boolean; children: React.ReactNode }) {
  return (
    <li className="flex gap-3">
      <span className={cn('flex size-7 shrink-0 items-center justify-center rounded-full text-xs font-semibold',
        done ? 'bg-good text-white' : 'bg-accent text-accent-fg')}>{done ? <Check className="size-4" /> : n}</span>
      <div className="flex min-w-0 flex-1 flex-col gap-2 pb-2">
        <h3 className="text-sm font-medium">{title}</h3>
        {children}
      </div>
    </li>
  )
}

function CopyBox({ value, label, mono = true }: { value: string; label: string; mono?: boolean }) {
  return (
    <div className="flex items-center gap-2 rounded-control border border-border bg-surface-2 p-1.5 pl-3">
      <span className={cn('min-w-0 flex-1 truncate text-sm', mono && 'font-mono')} title={value}>{value}</span>
      <Button size="sm" variant="outline" aria-label={`Copy ${label}`}
        onClick={() => void navigator.clipboard.writeText(value).then(() => toast.success(`${label} copied`))}><Copy />Copy</Button>
    </div>
  )
}

const OUTCOME: Record<ReceivedEmail['result'], { label: string; tone: 'good' | 'muted' | 'warn' }> = {
  added: { label: 'Added to review', tone: 'good' },
  'added-automatically': { label: 'Added on its own', tone: 'good' },
  'unreadable-kept': { label: 'Kept to look at', tone: 'warn' },
  'not-a-transaction': { label: 'Skipped: not a transaction', tone: 'muted' },
  duplicate: { label: 'Already had it', tone: 'muted' },
  'gmail-confirmation': { label: 'Gmail’s confirmation', tone: 'muted' },
  'over-daily-limit': { label: 'Over today’s limit', tone: 'warn' },
  unreadable: { label: 'Couldn’t open it', tone: 'warn' },
}

const ago = (iso: string) => {
  const s = Math.round((Date.now() - new Date(iso).getTime()) / 1000)
  if (s < 60) return 'just now'
  if (s < 3600) return `${Math.round(s / 60)} min ago`
  if (s < 86_400) return `${Math.round(s / 3600)} h ago`
  return new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
}

/**
 * Once forwarding works, the setup steps step aside: one card says it's on, when the last email came, and what came
 * of the recent ones (so a skipped email is never a mystery). "Show setup" brings the steps back.
 */
function EmailGuide({ data }: { data: CaptureView }) {
  const [setup, setSetup] = useState(false)
  if (data.emailReady && data.forwardingOn && !setup) return <ForwardingOn data={data} onSetup={() => setSetup(true)} />
  return <EmailSetup data={data} onDone={data.forwardingOn ? () => setSetup(false) : undefined} />
}

function ForwardingOn({ data, onSetup }: { data: CaptureView; onSetup: () => void }) {
  const [open, setOpen] = useState(true)
  return (
    <Card className="flex flex-col gap-4 p-pad" data-testid="forwarding-status">
      <div className="flex flex-wrap items-start gap-3">
        <span className="relative flex size-11 shrink-0 items-center justify-center rounded-full bg-good/15 text-good-text">
          <Mail className="size-5" />
          <CheckCircle2 className="absolute -right-0.5 -bottom-0.5 size-4 rounded-full bg-surface text-good-text" />
        </span>
        <div className="min-w-0 flex-1">
          <p className="font-medium">Email forwarding is on</p>
          <p className="text-sm text-muted">{data.lastEmailAt ? <>Last email {ago(data.lastEmailAt)} · {data.emailsThisWeek} this week</> : 'Waiting for the first bank alert.'}</p>
        </div>
        <Button variant="ghost" size="sm" onClick={onSetup}><Settings2 />Show setup</Button>
      </div>
      <CopyBox value={data.address} label="Address" />
      {data.recentEmails.length > 0 && (
        <div className="flex flex-col gap-1">
          <button type="button" onClick={() => setOpen(!open)} aria-expanded={open} className="flex items-center gap-1 self-start text-sm font-medium">
            <ChevronDown className={cn('size-4 transition-transform', !open && '-rotate-90')} />Recent emails
          </button>
          {open && (
            <ul className="flex flex-col divide-y divide-border" data-testid="recent-emails">
              {data.recentEmails.map((e) => {
                const o = OUTCOME[e.result] ?? { label: e.result, tone: 'muted' as const }
                return (
                  <li key={e.id} className="flex items-center gap-3 py-2 text-sm">
                    <span className="w-16 shrink-0 text-xs text-subtle tabular">{ago(e.receivedAt)}</span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate">{e.subject || '(no subject)'}</span>
                      {e.sender && <span className="block truncate text-xs text-subtle">{e.sender}</span>}
                    </span>
                    <span className={cn('shrink-0 rounded-full px-2 py-0.5 text-[0.6875rem] font-medium',
                      o.tone === 'good' ? 'bg-good/15 text-good-text' : o.tone === 'warn' ? 'bg-warning/15 text-fg' : 'bg-surface-2 text-muted')}>{o.label}</span>
                  </li>
                )
              })}
            </ul>
          )}
          <p className="text-xs text-subtle">Offers, OTPs and reminders from banks are skipped. Anything you forward by hand is always kept for you to look at.</p>
        </div>
      )}
    </Card>
  )
}

function EmailSetup({ data, onDone }: { data: CaptureView; onDone?: () => void }) {
  const reset = useResetAddress()
  const confirmed = useForwardingConfirmed()
  return (
    <Card className="flex flex-col gap-5 p-pad">
      {onDone && <Button variant="ghost" size="sm" className="self-end" onClick={onDone}><ChevronUp />Hide setup</Button>}
      <ForwardFlow />
      {!data.emailReady ? (
        <div className="flex items-start gap-3 rounded-control bg-warning/10 p-4 text-sm" data-testid="email-not-ready">
          <AlertTriangle className="mt-0.5 size-5 shrink-0 text-warning" />
          <div>
            <p className="font-medium">Email forwarding isn’t set up on this site yet</p>
            <p className="mt-1 text-muted">Your address would be on a domain that can’t receive mail yet, so Gmail’s confirmation and your bank
              alerts wouldn’t arrive. The site’s owner is setting it up. Meanwhile, pasting bank SMS and importing a statement work now.</p>
          </div>
        </div>
      ) : (
      <ol className="flex flex-col gap-3">
        <Step n={1} title="Your own PayLedger address">
          <CopyBox value={data.address} label="Address" />
          <p className="text-xs text-subtle">Only for you. Anything sent to it lands in your To review list.
            {' '}<button type="button" className="underline underline-offset-4" onClick={() => reset.mutate(undefined, { onSuccess: () => toast.success('New address made', { description: 'The old one no longer works. Update the forwarding in Gmail.' }) })}>Make a new one</button></p>
        </Step>
        <Step n={2} title="Add it as a forwarding address in Gmail">
          <div className="grid gap-3 sm:grid-cols-[1fr_16rem] sm:items-start">
            <ol className="list-decimal space-y-1 pl-4 text-sm text-muted">
              <li>Open <a className="text-fg underline underline-offset-4" href="https://mail.google.com/mail/u/0/#settings/fwdandpop" target="_blank" rel="noreferrer">Gmail's Forwarding settings<ExternalLink className="ml-0.5 inline size-3" /></a> on a computer.</li>
              <li>Click <b className="text-fg">Add a forwarding address</b>, paste your address, then <b className="text-fg">Next → Proceed</b>.</li>
              <li>Gmail sends a confirmation code to that address. It shows up in step 3 by itself.</li>
            </ol>
            <GmailSettings step="forwarding" />
          </div>
        </Step>
        <Step n={3} title={data.forwardingOn ? 'Forwarding is on' : 'Confirm it'} done={data.forwardingOn}>
          {data.forwardingOn ? (
            <p className="flex items-center gap-2 text-sm text-good-text" data-testid="forwarding-on"><CheckCircle2 className="size-4" />
              {data.lastEmailAt ? `Emails are arriving. The last one came ${new Date(data.lastEmailAt).toLocaleString()}.` : 'Confirmed. Bank alerts will show up in To review as they come.'}</p>
          ) : data.confirmation ? (
            <div className="flex flex-col gap-2">
              <p className="text-sm text-muted">Gmail asked you to confirm. Open its link (or enter its code in Gmail's settings), then come back:</p>
              {data.confirmation.code && <CopyBox value={data.confirmation.code} label="Code" />}
              <div className="flex flex-wrap gap-2">
                {data.confirmation.link && <a href={data.confirmation.link} target="_blank" rel="noreferrer"><Button size="sm" variant="outline"><ExternalLink />Confirm with Gmail's link</Button></a>}
                {/* Gmail never tells us it was confirmed; the first forwarded email would, this says it sooner. */}
                <Button size="sm" variant="primary" disabled={confirmed.isPending} onClick={() => confirmed.mutate(undefined, { onSuccess: () => toast.success('Forwarding is on') })}>
                  <CheckCircle2 />I've confirmed it in Gmail</Button>
              </div>
            </div>
          ) : (
            <p className="flex items-center gap-2 text-sm text-muted"><Loader2 className="size-4 animate-spin" />Waiting for Gmail's code. This page checks every few seconds.</p>
          )}
        </Step>
        <Step n={4} title="Forward only bank alerts, with a filter" done={!!data.lastEmailAt}>
          <BankFilter />
        </Step>
      </ol>
      )}
    </Card>
  )
}

/** The Gmail filter, built from the banks you tick: their alert addresses joined with OR, for the From box. */
function BankFilter() {
  const [banks, setBanks] = useState<string[]>(() => {
    try { return JSON.parse(localStorage.getItem('payledger.capture.banks') ?? '') as string[] } catch { return ['HDFC Bank'] }
  })
  const pick = (bank: string) => {
    const next = banks.includes(bank) ? banks.filter((b) => b !== bank) : [...banks, bank]
    setBanks(next)
    try { localStorage.setItem('payledger.capture.banks', JSON.stringify(next)) } catch { /* only a convenience */ }
  }
  const filter = bankFilter(banks)
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-col gap-2">
        <span className="text-sm text-muted">1. Tick your banks:</span>
        <div className="flex flex-wrap gap-1.5" role="group" aria-label="Your banks">
          {BANK_SENDERS.map((b) => (
            <button key={b.bank} type="button" aria-pressed={banks.includes(b.bank)} onClick={() => pick(b.bank)}
              className={cn('rounded-full border px-3 py-1 text-xs', banks.includes(b.bank) ? 'border-accent bg-accent-soft font-medium' : 'border-border text-muted hover:border-border-strong')}>
              {banks.includes(b.bank) && <Check className="mr-1 inline size-3" />}{b.bank}</button>
          ))}
        </div>
      </div>
      <div className="grid gap-3 sm:grid-cols-[1fr_16rem] sm:items-start">
        <ol start={2} className="list-decimal space-y-1 pl-4 text-sm text-muted">
          <li>In Gmail, open the search options (the sliders icon in the search box).</li>
          <li>Paste this into the <b className="text-fg">From</b> box, as it is:</li>
        </ol>
        <GmailSettings step="filter" />
      </div>
      {filter ? (
        <>
          <CopyBox value={filter} label="Filter" />
          <p className="text-xs text-subtle">Gmail then shows the filter as <span className="break-all font-mono">Matches: from:({filter})</span>.</p>
        </>
      ) : <p className="text-sm text-critical">Tick at least one bank.</p>}
      <ol start={4} className="list-decimal space-y-1 pl-4 text-sm text-muted">
        <li>Click <b className="text-fg">Create filter</b>, then tick:
          <ul className="mt-1 list-disc space-y-0.5 pl-4">
            <li><b className="text-fg">Forward it to</b>: your PayLedger address</li>
            <li><b className="text-fg">Never send it to Spam</b>, and if you like <b className="text-fg">Mark it as important</b> and <b className="text-fg">Categorise as: Primary</b>, so alerts stay in view in Gmail too</li>
          </ul>
        </li>
        <li>Your bank's alert address not listed? Add it at the end, like <span className="font-mono">OR alerts@yourbank.com</span> (OR between addresses, not commas). It's the "From" address on any alert email from them.</li>
      </ol>
      <p className="text-xs text-subtle">Only these emails are forwarded: the rest of your mailbox stays yours. To stop, delete the filter in Gmail.</p>
    </div>
  )
}

function PasteBox() {
  const paste = usePaste()
  const [text, setText] = useState('')
  async function read() {
    try {
      const r = await paste.mutateAsync({ text })
      if (!r.added && !r.unreadable) {
        toast(r.duplicates ? 'Already here' : 'Nothing to add', { description: r.duplicates ? 'You pasted these before.' : "These don't look like bank transactions (OTPs and offers are skipped)." })
        return
      }
      toast.success(`${r.added} ${r.added === 1 ? 'entry' : 'entries'} to review`,
        { description: [r.duplicates && `${r.duplicates} already here`, r.unreadable && `${r.unreadable} couldn't be read`].filter(Boolean).join(' · ') || 'Check them above, then Add.' })
      setText('')
    } catch (e) {
      toast.error("Couldn't read them", { description: e instanceof Error ? e.message : undefined })
    }
  }
  return (
    <Card className="grid gap-5 p-pad sm:grid-cols-[1fr_11rem]">
      <div className="flex flex-col gap-3">
        <ol className="list-decimal space-y-1 pl-4 text-sm text-muted">
          <li>In Messages, open your bank's chat and long-press an alert, then <b className="text-fg">Copy</b>. Some phones let you pick several.</li>
          <li>Paste below. Many at once is fine: one per line, or with a blank line between.</li>
          <li>Press <b className="text-fg">Read them</b>, check each entry above, and Add.</li>
        </ol>
        <textarea value={text} onChange={(e) => setText(e.target.value)} rows={6} aria-label="Bank SMS"
          placeholder={'Rs.450.00 debited from a/c **1234 on 14-10-26 to VPA swiggy@icici - HDFC Bank\n\nINR 1,20,000.00 credited to A/c XX1234 on 01-10-26 ...'}
          className="w-full rounded-control border border-border-strong bg-surface p-3 text-sm focus:border-accent focus:outline-none" />
        <div className="flex flex-wrap items-center gap-2">
          <Button variant="primary" onClick={() => void read()} disabled={!text.trim() || paste.isPending}>
            {paste.isPending ? <Loader2 className="animate-spin" /> : <Sparkles />}Read them
          </Button>
          <span className="text-xs text-subtle">OTPs, offers and bill reminders are skipped by themselves.</span>
        </div>
      </div>
      <PhoneSms className="mx-auto hidden sm:block" />
    </Card>
  )
}

function ShareGuide() {
  return (
    <Card className="grid gap-5 p-pad sm:grid-cols-[1fr_11rem]">
      <ol className="flex flex-col gap-3">
        <Step n={1} title="Install PayLedger on your phone">
          <p className="text-sm text-muted">Open this site in Chrome on Android, tap the <b className="text-fg">⋮</b> menu, then <b className="text-fg">Install app</b> (or Add to Home screen).</p>
        </Step>
        <Step n={2} title="Share a bank SMS to it">
          <p className="text-sm text-muted">In Messages, long-press an alert, tap <b className="text-fg">Share</b> and choose <b className="text-fg">PayLedger</b>. It opens here, already read.</p>
        </Step>
        <Step n={3} title="Check and add">
          <p className="text-sm text-muted">The entry waits in To review with the account and category guessed. Tap Add.</p>
        </Step>
        <p className="flex items-center gap-2 text-xs text-subtle"><RefreshCw className="size-3.5" />iPhone can't share SMS to web apps: use Paste SMS, or forward bank emails.</p>
      </ol>
      <PhoneSms share className="mx-auto" />
    </Card>
  )
}

/** A statement file from net banking: every row goes into To review, from the account chosen here. */
function StatementBox() {
  const statement = useStatement()
  const personalSpace = usePersonalSpaceId()
  const { data: accounts } = useAccounts(false, personalSpace)
  const money = useMoneyFormat()
  const [file, setFile] = useState<File | null>(null)
  const [account, setAccount] = useState('')
  const [password, setPassword] = useState('')
  const [drag, setDrag] = useState(false)
  const [error, setError] = useState<string>()
  const [needsPassword, setNeedsPassword] = useState(false)
  const result = statement.data
  const payable = (accounts ?? []).filter((a) => a.kind !== 'OWED' && !a.archived)
  const pdf = !!file && file.name.toLowerCase().endsWith('.pdf')

  function pick(f: File | undefined) {
    if (!f) return
    setFile(f)
    setError(undefined)
    setNeedsPassword(false)
    statement.reset()
  }

  async function read() {
    if (!file || !account) return
    setError(undefined)
    try {
      const r = await statement.mutateAsync({ file, accountId: account, password: password || undefined })
      toast.success(r.added ? `${r.added} entries to review` : 'Nothing new', { description: r.duplicates ? `${r.duplicates} were here already.` : undefined })
      setPassword('')
    } catch (e) {
      if (e instanceof ApiError && (e.code === 'statement-password' || e.code === 'statement-wrong-password')) setNeedsPassword(true)
      setError(e instanceof ApiError ? e.message : "Couldn't read it. Try the CSV or Excel download.")
    }
  }

  return (
    <Card className="flex flex-col gap-5 p-pad">
      <div className="grid gap-5 md:grid-cols-[1fr_15rem]">
        <div className="flex flex-col gap-3">
          <label onDragOver={(e) => { e.preventDefault(); setDrag(true) }} onDragLeave={() => setDrag(false)}
            onDrop={(e) => { e.preventDefault(); setDrag(false); pick(e.dataTransfer.files[0]) }}
            className={cn('flex cursor-pointer flex-col items-center gap-2 rounded-card border-2 border-dashed p-6 text-center transition-colors',
              drag ? 'border-accent bg-accent-soft/50' : 'border-border-strong hover:border-accent')}>
            <FileUp className="size-8 text-accent" />
            {file ? <span className="text-sm font-medium">{file.name} <span className="text-subtle">({Math.max(1, Math.round(file.size / 1024))} KB)</span></span>
              : <span className="text-sm"><b>Drop the statement here</b> or choose it</span>}
            <span className="text-xs text-subtle">CSV, Excel (.xls, .xlsx) or PDF · up to 5 MB</span>
            <input type="file" accept=".csv,.txt,.xls,.xlsx,.pdf" className="sr-only" aria-label="Statement file" onChange={(e) => pick(e.target.files?.[0])} />
          </label>
          <div className="grid gap-2 sm:grid-cols-2">
            <AccountPicker space={personalSpace} aria-label="Statement account" value={account} onChange={setAccount} options={accountOptions(payable)} placeholder="Which account is it for?" />
            {(pdf || needsPassword) && (
              <Input type="password" aria-label="PDF password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="off"
                placeholder="PDF password, if it has one" />
            )}
          </div>
          {pdf && <p className="text-xs text-subtle">Banks often protect PDFs with your date of birth or part of your customer id. The password is used once to open the file and never kept.</p>}
          <div className="flex flex-wrap items-center gap-2">
            <Button variant="primary" onClick={() => void read()} disabled={!file || !account || statement.isPending}>
              {statement.isPending ? <Loader2 className="animate-spin" /> : <Sparkles />}Read the statement
            </Button>
            {payable.length === 0 && <Link to="/accounts" className="text-xs underline underline-offset-4">Add the account first</Link>}
          </div>
          {error && <p className="rounded-control bg-critical/10 px-3 py-2 text-sm text-critical" role="alert">{error}</p>}
          {result && (
            <div className="flex flex-col gap-1 rounded-control bg-surface-2 p-3 text-sm" data-testid="statement-result">
              <span className="font-medium">{result.bank ?? 'Statement'} · {dayLabel(result.from)} to {dayLabel(result.to)}</span>
              <span className="text-muted">{result.rows} rows: <b className="text-fg">{result.added} new</b> to review{result.duplicates ? `, ${result.duplicates} already here` : ''}.</span>
              {result.adds === true && <span className="flex items-center gap-1.5 text-good-text"><Check className="size-4" />Balances add up: {money(result.opening ?? 0)} to {money(result.closing ?? 0)}.</span>}
              {result.adds === false && <span className="flex items-center gap-1.5 text-warning"><AlertTriangle className="size-4" />The balances don't add up: some rows may be missing. Check the file covers the whole period.</span>}
            </div>
          )}
        </div>
        <div className="flex flex-col gap-2 rounded-control bg-surface-2 p-3 text-sm">
          <span className="font-medium">Where to get it</span>
          <ol className="list-decimal space-y-1 pl-4 text-xs text-muted">
            <li>Sign in to your bank's net banking on a computer.</li>
            <li>Open the account, then <b className="text-fg">Account statement</b> (or "Download statement").</li>
            <li>Choose the period, up to a year.</li>
            <li>Download as <b className="text-fg">Excel or CSV</b> if offered (most reliable), else PDF.</li>
          </ol>
          <span className="text-xs text-subtle">Works with HDFC, ICICI, SBI, Axis, Kotak and most others. Every row waits for your check; the same file twice adds nothing.</span>
        </div>
      </div>
    </Card>
  )
}
