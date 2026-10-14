import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { AlertTriangle, ArrowDownLeft, ArrowUpRight, Check, ChevronDown, Copy, ExternalLink, Inbox, Loader2, RefreshCw, ShieldCheck, Sparkles, X } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Badge, Card, Skeleton } from '@/components/ui/card'
import { Input, Segmented } from '@/components/ui/controls'
import { SearchSelect } from '@/components/ui/search-select'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/cn'
import { minorToInput, parseMoney } from '@/lib/money'
import { accountOptions, categoryOptions, dayLabel, today, useMoneyFormat } from '@/pages/money/parts'
import { BANK_FILTER, useAddCaptured, useCapture, useDismissCaptured, usePaste, useResetAddress, type CaptureView, type CapturedItem } from '@/state/capture'
import { useAccounts, useCategories } from '@/state/money'
import { ForwardFlow, GmailSettings, MethodIcon, PhoneSms } from './illustrations'

type Method = 'email' | 'paste' | 'share'

/**
 * Auto-capture: bank alerts come in without typing (forwarded emails, pasted or shared SMS) and wait here to be
 * checked. Nothing reaches your accounts until you add it. The guide shows each way in, step by step.
 */
export function CapturePage() {
  const search = useSearch({ from: '/app/capture' })
  const navigate = useNavigate()
  const [method, setMethod] = useState<Method>('email')
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
      {unreadable.length > 0 && <Unreadable items={unreadable} />}

      <section className="flex flex-col gap-3" aria-label="Ways to bring entries in">
        <h2 className="text-base font-semibold">{pending.length ? 'Bring more in' : 'Set it up once'}</h2>
        <div className="grid gap-2 sm:grid-cols-3" role="tablist">
          {([
            { key: 'email', title: 'Forward bank emails', text: 'Automatic, once set up. Best on a computer.', tag: 'Recommended' },
            { key: 'paste', title: 'Paste SMS', text: 'Copy one or many bank SMS and paste them here.' },
            { key: 'share', title: 'Share from Messages', text: 'Android: Share → PayLedger, after installing the app.' },
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
        {data && method === 'email' && <EmailGuide data={data} />}
        {method === 'paste' && <PasteBox />}
        {method === 'share' && <ShareGuide />}
      </section>

      {decided.length > 0 && (
        <section className="flex flex-col gap-2" aria-label="Recently decided">
          <h2 className="text-sm font-medium text-muted">Recently</h2>
          <Card className="divide-y divide-border">
            {decided.map((i) => (
              <div key={i.id} className="flex items-center gap-3 px-pad py-2 text-sm">
                {i.status === 'ADDED' ? <Check className="size-4 text-good-text" /> : <X className="size-4 text-subtle" />}
                <span className="min-w-0 flex-1 truncate">{i.merchant ?? i.bank ?? 'Bank alert'}</span>
                {i.status === 'ADDED' && i.transactionId
                  ? <Link to="/transactions" search={{ open: i.transactionId }} className="text-xs underline underline-offset-4">Open</Link>
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

// ---------------------------------------------------------------- review

function ReviewList({ items }: { items: CapturedItem[] }) {
  const add = useAddCaptured()
  const [busy, setBusy] = useState(false)
  const ready = items.filter((i) => i.accountId && !i.duplicateOf)

  async function addAll() {
    setBusy(true)
    let n = 0
    for (const i of ready) {
      try {
        await add.mutateAsync({ id: i.id, kind: i.kind!, amount: i.amount!, accountId: i.accountId!, categoryId: i.categoryId,
          occurredOn: i.occurredOn ?? today(), description: i.merchant ?? i.bank ?? 'From a bank alert' })
        n++
      } catch { /* left in the list for a closer look */ }
    }
    setBusy(false)
    toast.success(`${n} added`, { description: n < ready.length ? 'The rest need a look.' : undefined })
  }

  return (
    <section className="flex flex-col gap-3" aria-label="To review">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="flex items-center gap-2 text-base font-semibold"><Inbox className="size-5 text-accent" />To review</h2>
        {ready.length > 1 && (
          <Button size="sm" variant="primary" onClick={() => void addAll()} disabled={busy}>
            {busy ? <Loader2 className="animate-spin" /> : <Check />}Add all {ready.length} that look right
          </Button>
        )}
      </div>
      <div className="flex flex-col gap-3">
        {items.map((i) => <ReviewCard key={i.id} item={i} />)}
      </div>
    </section>
  )
}

function ReviewCard({ item }: { item: CapturedItem }) {
  const { data: accounts } = useAccounts()
  const { data: categories } = useCategories()
  const add = useAddCaptured()
  const dismiss = useDismissCaptured()
  const money = useMoneyFormat()
  const [kind, setKind] = useState<'EXPENSE' | 'INCOME'>(item.kind ?? 'EXPENSE')
  const [description, setDescription] = useState(item.merchant ?? item.bank ?? '')
  const [amount, setAmount] = useState(item.amount ? minorToInput(item.amount, 'INR') : '')
  const [date, setDate] = useState(item.occurredOn ?? today())
  const [account, setAccount] = useState(item.accountId ?? '')
  const [category, setCategory] = useState(item.categoryId ?? '')
  const [open, setOpen] = useState(false)
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
      await add.mutateAsync({ id: item.id, kind, amount: value, accountId: account, categoryId: category || null, occurredOn: date,
        description: description.trim() || (out ? 'Spent' : 'Received') })
      toast.success(`${description || 'Entry'} added`)
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
          <span className="block truncate font-medium">{item.merchant ?? 'Someone'}</span>
          <span className="block truncate text-xs text-subtle">
            {[item.bank, item.accountHint && `••${item.accountHint}`, item.occurredOn && dayLabel(item.occurredOn), item.source === 'EMAIL' ? 'email' : 'SMS'].filter(Boolean).join(' · ')}
          </span>
        </span>
        <span className={cn('shrink-0 text-lg font-semibold tabular', out ? 'text-[var(--flow-out)]' : 'text-[var(--flow-in)]')}>
          {out ? '−' : '+'}{money(item.amount ?? 0)}
        </span>
      </div>

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
        <SearchSelect aria-label="Account" value={account} onChange={setAccount} options={accountOptions(payable)} placeholder="Which account?" />
        <SearchSelect aria-label="Category" value={category} onChange={setCategory} options={categoryOptions(choices, categories ?? [])}
          placeholder="Category (else Other)" />
        <Input aria-label="Amount" inputMode="decimal" className="tabular" value={amount} onChange={(e) => setAmount(e.target.value)} />
        <Input aria-label="Date" type="date" value={date} onChange={(e) => setDate(e.target.value)} />
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

function Unreadable({ items }: { items: CapturedItem[] }) {
  const dismiss = useDismissCaptured()
  return (
    <section className="flex flex-col gap-2" aria-label="Couldn't read">
      <h2 className="text-sm font-medium text-muted">Couldn't read these</h2>
      <Card className="divide-y divide-border">
        {items.map((i) => (
          <div key={i.id} className="flex flex-col gap-2 px-pad py-3">
            <p className="line-clamp-3 text-xs text-muted">{i.subject ?? i.body}</p>
            <span className="flex gap-2 self-end">
              <Button size="sm" variant="ghost" onClick={() => dismiss.mutate(i.id)}>Dismiss</Button>
              <Link to="/transactions" search={{ add: 'EXPENSE' }}><Button size="sm" variant="outline">Add by hand</Button></Link>
            </span>
          </div>
        ))}
      </Card>
    </section>
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

function EmailGuide({ data }: { data: CaptureView }) {
  const reset = useResetAddress()
  return (
    <Card className="flex flex-col gap-5 p-pad">
      <ForwardFlow />
      {!data.emailReady && (
        <p className="flex items-start gap-2 rounded-control bg-warning/10 px-3 py-2 text-sm">
          <AlertTriangle className="mt-0.5 size-4 shrink-0 text-warning" />
          Email forwarding isn't switched on for this site yet, so emails sent to the address won't arrive. Pasting SMS works now.
        </p>
      )}
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
        <Step n={3} title="Confirm it" done={!!data.confirmation || !!data.lastEmailAt}>
          {data.confirmation ? (
            <div className="flex flex-col gap-2">
              <p className="text-sm text-muted">Gmail's code arrived. Enter it in Gmail's settings, or open Gmail's link:</p>
              {data.confirmation.code && <CopyBox value={data.confirmation.code} label="Code" />}
              {data.confirmation.link && <a href={data.confirmation.link} target="_blank" rel="noreferrer"><Button size="sm" variant="outline"><ExternalLink />Confirm with Gmail's link</Button></a>}
            </div>
          ) : data.lastEmailAt ? (
            <p className="text-sm text-good-text">Emails are arriving. The last one came {new Date(data.lastEmailAt).toLocaleString()}.</p>
          ) : (
            <p className="flex items-center gap-2 text-sm text-muted"><Loader2 className="size-4 animate-spin" />Waiting for Gmail's code. This page checks every few seconds.</p>
          )}
        </Step>
        <Step n={4} title="Forward only bank alerts, with a filter" done={!!data.lastEmailAt}>
          <div className="grid gap-3 sm:grid-cols-[1fr_16rem] sm:items-start">
            <ol className="list-decimal space-y-1 pl-4 text-sm text-muted">
              <li>In Gmail's search box, open the filter options (the sliders icon).</li>
              <li>Paste this into <b className="text-fg">From</b>:</li>
            </ol>
            <GmailSettings step="filter" />
          </div>
          <CopyBox value={BANK_FILTER} label="Filter" />
          <ol start={3} className="list-decimal space-y-1 pl-4 text-sm text-muted">
            <li>Click <b className="text-fg">Create filter</b>, tick <b className="text-fg">Forward it to</b> and choose your PayLedger address.</li>
            <li>Bank missing? Add its alert address to the list, like <span className="font-mono">OR alerts@yourbank.com</span>.</li>
          </ol>
          <p className="text-xs text-subtle">Only these emails are forwarded: the rest of your mailbox stays yours. To stop, delete the filter in Gmail.</p>
        </Step>
      </ol>
    </Card>
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
