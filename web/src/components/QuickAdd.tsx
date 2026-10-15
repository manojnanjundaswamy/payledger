import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { useQueryClient } from '@tanstack/react-query'
import { AnimatePresence, motion } from 'motion/react'
import { ArrowDownLeft, ArrowLeftRight, ArrowUpRight, Check, Loader2, Mic, PenLine, Sparkles, Square, Undo2, X } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/card'
import { Input } from '@/components/ui/controls'
import { MoneyInput } from '@/components/ui/money-input'
import { Dialog } from '@/components/ui/overlay'
import { SearchSelect } from '@/components/ui/search-select'
import { useDictation } from '@/hooks/useDictation'
import { api } from '@/lib/api'
import { cn } from '@/lib/cn'
import { minorToInput, parseMoney } from '@/lib/money'
import { accountOptions, categoryOptions, dayLabel, useMoneyFormat } from '@/pages/money/parts'
import { type QuickEntry, type QuickAddResult, useAiStatus, useQuickAdd } from '@/state/ai'
import { useAccounts, useCategories, usePersonalSpaceId } from '@/state/money'
import { useProfile } from '@/state/profile'

const OPEN = 'payledger:quick-add'

/** Opens quick add from anywhere (the Add button, the phone's + button, the dashboard bar). */
export function openQuickAdd(opts: { listen?: boolean; text?: string } = {}) {
  window.dispatchEvent(new CustomEvent(OPEN, { detail: opts }))
}

const EXAMPLES = ['450 petrol yesterday on HDFC card', 'Got 50k salary today', 'Paid Ravi 1,200 for dinner', '200 auto and 350 lunch', 'Moved 10k from HDFC to Axis']

/**
 * Say it or type it: the fastest way to add. Words appear as you speak; Add sends them (masked) to be read. What we're
 * at least 95% sure of is added at once, with Undo; the rest comes back filled in, to check and add with one tap.
 */
export function QuickAdd() {
  const [open, setOpen] = useState(false)
  const [text, setText] = useState('')
  /** Words still being heard (may change); shown dimmed after the settled ones. */
  const [interim, setInterim] = useState('')
  const [channel, setChannel] = useState<'TYPED' | 'VOICE'>('TYPED')
  const [result, setResult] = useState<QuickAddResult | null>(null)
  const [example, setExample] = useState(0)
  const pendingListen = useRef(false)
  const box = useRef<HTMLTextAreaElement>(null)
  const caption = useRef<HTMLDivElement>(null)
  const status = useAiStatus()
  const quickAdd = useQuickAdd()
  const locale = useProfile().data?.settings.locale ?? 'en-IN'
  const dictation = useDictation({
    lang: locale,
    serverCanTranscribe: !!status.data?.speechToText,
    onText: (committed, heard) => { setText(committed); setInterim(heard); setChannel('VOICE') },
  })
  const listening = dictation.state === 'listening'
  const spoken = `${text} ${interim}`.trim()

  useEffect(() => {
    const onOpen = (e: Event) => {
      const d = (e as CustomEvent<{ listen?: boolean; text?: string }>).detail ?? {}
      setResult(null)
      setText(d.text ?? '')
      setInterim('')
      setChannel('TYPED')
      setOpen(true)
      pendingListen.current = !!d.listen
    }
    window.addEventListener(OPEN, onOpen)
    return () => window.removeEventListener(OPEN, onOpen)
  }, [])

  // Opened from a mic button: start listening once the sheet is up.
  useEffect(() => {
    if (open && pendingListen.current && dictation.mode !== 'none') {
      pendingListen.current = false
      void dictation.start('')
    }
  }, [open, dictation])

  useEffect(() => {
    if (!open || spoken || listening) return
    const t = window.setInterval(() => setExample((i) => (i + 1) % EXAMPLES.length), 2800)
    return () => window.clearInterval(t)
  }, [open, spoken, listening])

  // The newest words stay in view while speaking.
  useEffect(() => { caption.current?.scrollTo({ top: caption.current.scrollHeight }) }, [spoken])

  const close = (o: boolean) => {
    if (!o) dictation.stop()
    setOpen(o)
  }

  async function submit() {
    // Add while still talking: what's been heard so far, including the words still settling.
    const said = spoken
    if (listening) dictation.stop()
    if (!said || quickAdd.isPending) return
    setText(said)
    setInterim('')
    try {
      const r = await quickAdd.mutateAsync({ text: said, channel })
      setResult(r)
      const added = r.entries.filter((e) => e.status === 'ADDED').length
      if (added && added === r.entries.length) {
        toast.success(added === 1 ? 'Added' : `Added ${added}`, { description: 'Undo is right there if it’s wrong.' })
      }
    } catch {
      toast.error("Couldn't read that just now", { description: 'Try again, or add it by hand.' })
    }
  }

  const usage = status.data?.usage
  const clock = `${Math.floor(dictation.elapsed / 60)}:${String(dictation.elapsed % 60).padStart(2, '0')}`
  return (
    <Dialog open={open} onOpenChange={close} title="Say it or type it" description="Like you'd tell a friend. We'll fill in the rest.">
      <div className="flex max-h-[78dvh] flex-col gap-4 overflow-y-auto p-pad">
        {!result && listening && (
          <div className="flex flex-col gap-4" aria-live="polite">
            <div className="flex items-center justify-between text-xs text-muted">
              <span className="inline-flex items-center gap-2">
                <span className="relative flex size-2.5"><span className="absolute inline-flex size-full animate-ping rounded-full bg-critical opacity-60" /><span className="relative inline-flex size-2.5 rounded-full bg-critical" /></span>
                {dictation.mode === 'record' ? 'Recording' : 'Listening'} · pause as long as you like
              </span>
              <span className="tabular">{clock}</span>
            </div>
            <div ref={caption} className="min-h-32 max-h-56 overflow-y-auto rounded-card bg-surface-2 px-4 py-4 text-xl leading-relaxed font-medium tracking-tight">
              {spoken
                ? <><span>{text}</span>{interim && <span className="text-muted"> {interim}</span>}<span aria-hidden className="ml-0.5 inline-block h-6 w-0.5 translate-y-1 animate-pulse bg-accent" /></>
                : <span className="text-subtle">{dictation.mode === 'record' ? 'Speak now. Your words appear when you stop.' : `Say something like “${EXAMPLES[example]}”`}</span>}
            </div>
            <Waveform active={dictation.hearing} level={dictation.level} />
            <div className="grid grid-cols-2 gap-2">
              <Button variant="secondary" size="lg" onClick={() => dictation.stop()}><Square className="fill-current" />Stop</Button>
              <Button variant="primary" size="lg" disabled={!spoken || quickAdd.isPending} onClick={() => void submit()}><Sparkles />Add it</Button>
            </div>
          </div>
        )}
        {!result && !listening && (
          <>
            {dictation.state === 'transcribing' ? (
              <div className="flex flex-col items-center gap-2 py-6 text-sm text-muted"><Loader2 className="size-6 animate-spin" />Turning that into text…</div>
            ) : (
              <div className="flex flex-col items-center gap-2 pt-1">
                <MicButton disabled={dictation.mode === 'none'} onClick={() => void dictation.start(text)} />
                <p className="text-center text-sm text-muted">
                  {dictation.mode === 'none' ? 'Type below (this browser can’t take dictation)' : spoken ? 'Tap to keep talking' : 'Tap and speak'}
                </p>
              </div>
            )}
            {dictation.error && <p className="text-center text-sm text-critical">{dictation.error}</p>}
            <textarea ref={box} value={text} rows={3} aria-label="What you spent or received"
              onChange={(e) => { setText(e.target.value); setChannel('TYPED') }}
              onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); void submit() } }}
              className="w-full resize-none rounded-card border border-border bg-surface-2 px-4 py-3 text-base leading-relaxed outline-none placeholder:text-subtle focus:border-border-strong"
              placeholder={`e.g. ${EXAMPLES[example]}`} />
            {!text && (
              <div className="flex flex-wrap justify-center gap-1.5">
                {EXAMPLES.slice(0, 3).map((ex) => (
                  <button key={ex} type="button" onClick={() => { setText(ex); box.current?.focus() }}
                    className="rounded-full border border-border px-3 py-1 text-xs text-muted hover:border-border-strong hover:text-fg">{ex}</button>
                ))}
              </div>
            )}
            <Button variant="primary" size="lg" className="w-full" disabled={!text.trim() || quickAdd.isPending} onClick={() => void submit()}>
              {quickAdd.isPending ? <><Loader2 className="animate-spin" />Reading…</> : <><Sparkles />Add</>}
            </Button>
            <ManualLinks onPick={() => close(false)} />
          </>
        )}
        {result && <Results result={result} onAgain={() => { setResult(null); setText(''); setInterim(''); setTimeout(() => box.current?.focus(), 50) }} onDone={() => close(false)} />}
        <p className="text-center text-xs text-subtle">
          {status.data && !status.data.enabled ? 'Simple reading: amount and words. Check before adding.'
            : usage?.limit != null ? `${Math.max(0, usage.limit - usage.used)} of ${usage.limit} AI reads left this month · Account numbers and names are hidden before reading`
            : 'Account numbers and names are hidden before reading'}
        </p>
      </div>
    </Dialog>
  )
}

function MicButton({ disabled, onClick }: { disabled: boolean; onClick: () => void }) {
  return (
    <div className="relative flex size-24 items-center justify-center">
      <motion.span aria-hidden className="absolute inset-1 rounded-full bg-accent/10"
        animate={{ scale: [1, 1.12, 1] }} transition={{ duration: 2.4, repeat: Infinity, ease: 'easeInOut' }} />
      <button type="button" onClick={onClick} disabled={disabled} aria-label="Speak an entry"
        className="relative flex size-20 items-center justify-center rounded-full bg-accent text-accent-fg shadow-lift transition-transform active:scale-95 disabled:opacity-40">
        <Mic className="size-8" />
      </button>
    </div>
  )
}

/** Bars that move while you speak, and rest while you pause: you can see it's still listening. */
function Waveform({ active, level }: { active: boolean; level: number }) {
  const bars = 28
  return (
    <div aria-hidden className="flex h-12 items-center justify-center gap-[3px]">
      {Array.from({ length: bars }, (_, i) => {
        const centre = 1 - Math.abs(i - (bars - 1) / 2) / (bars / 2)
        const peak = active ? 0.35 + Math.max(level, 0.5) * 0.65 * centre : 0.12
        return (
          <motion.span key={i} className="w-1 rounded-full bg-accent"
            animate={{ height: active ? [`${12 + peak * 20}%`, `${20 + peak * 80}%`, `${12 + peak * 30}%`] : '12%', opacity: active ? 1 : 0.35 }}
            transition={active ? { duration: 0.5 + ((i * 37) % 7) / 14, repeat: Infinity, repeatType: 'mirror', ease: 'easeInOut' } : { duration: 0.3 }} />
        )
      })}
    </div>
  )
}

/** The old way is still one tap away. */
function ManualLinks({ onPick }: { onPick: () => void }) {
  const navigate = useNavigate()
  const go = (kind: 'EXPENSE' | 'INCOME' | 'TRANSFER') => { onPick(); void navigate({ to: '/transactions', search: { add: kind } }) }
  return (
    <div className="flex flex-wrap items-center justify-center gap-x-3 gap-y-1 text-xs text-muted">
      <span className="inline-flex items-center gap-1"><PenLine className="size-3.5" />By hand:</span>
      <button type="button" className="inline-flex items-center gap-1 hover:text-fg" onClick={() => go('EXPENSE')}><ArrowUpRight className="size-3.5" />Expense</button>
      <button type="button" className="inline-flex items-center gap-1 hover:text-fg" onClick={() => go('INCOME')}><ArrowDownLeft className="size-3.5" />Income</button>
      <button type="button" className="inline-flex items-center gap-1 hover:text-fg" onClick={() => go('TRANSFER')}><ArrowLeftRight className="size-3.5" />Transfer</button>
    </div>
  )
}

function Results({ result, onAgain, onDone }: { result: QuickAddResult; onAgain: () => void; onDone: () => void }) {
  return (
    <div className="flex flex-col gap-3">
      {result.cleanText && (
        <p className="rounded-card bg-surface-2 px-3 py-2 text-sm text-muted">
          <span className="text-xs tracking-wide text-subtle uppercase">We heard</span><br />
          <span className="text-fg">{result.cleanText}</span>
        </p>
      )}
      {result.notice && <p className="rounded-card border border-warning/40 bg-warning/10 px-3 py-2 text-sm">{result.notice}</p>}
      {result.entries.length === 0 && !result.notice && <p className="text-sm text-muted">Nothing to add from that. Try “450 petrol yesterday”.</p>}
      <AnimatePresence initial>
        {result.entries.map((e, i) => (
          <motion.div key={i} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: i * 0.06 }}>
            {e.status === 'ADDED' ? <AddedCard entry={e} /> : <DraftCard entry={e} />}
          </motion.div>
        ))}
      </AnimatePresence>
      <div className="flex gap-2">
        <Button variant="secondary" className="flex-1" onClick={onAgain}><Mic />Add another</Button>
        <Button variant="primary" className="flex-1" onClick={onDone}>Done</Button>
      </div>
    </div>
  )
}

function Sure({ confidence }: { confidence: number }) {
  const pct = Math.round(confidence * 100)
  return <Badge tone={pct >= 95 ? 'good' : pct >= 75 ? 'warning' : 'neutral'} title="How sure we are that every detail is right">{pct}% sure</Badge>
}

function AddedCard({ entry }: { entry: QuickEntry }) {
  const fmt = useMoneyFormat()
  const space = usePersonalSpaceId()
  const accounts = useAccounts(false, space).data ?? []
  const categories = useCategories(space).data ?? []
  const client = useQueryClient()
  const [undone, setUndone] = useState(false)
  const [busy, setBusy] = useState(false)
  const account = accounts.find((a) => a.id === entry.accountId)?.name
  const category = categories.find((c) => c.id === entry.categoryId)?.name
  async function undo() {
    if (!space || !entry.transactionId || entry.version == null) return
    setBusy(true)
    try {
      await api<void>(`/api/core/spaces/${space}/transactions/${entry.transactionId}?version=${entry.version}`, { method: 'DELETE' })
      setUndone(true)
      void client.invalidateQueries()
    } catch {
      toast.error("Couldn't undo it", { description: 'Open it in Transactions to change or delete it.' })
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className={cn('flex items-center gap-3 rounded-card border p-3', undone ? 'border-border opacity-60' : 'border-good/40 bg-good/5')}>
      <span className={cn('flex size-9 shrink-0 items-center justify-center rounded-full', undone ? 'bg-surface-2 text-muted' : 'bg-good text-white')}>
        {undone ? <X className="size-4" /> : <Check className="size-4" />}
      </span>
      <div className="min-w-0 flex-1">
        <p className="flex flex-wrap items-baseline gap-x-2 font-medium">
          <span className={entry.kind === 'INCOME' ? 'text-good-text' : ''}>{fmt(entry.amount, entry.currency, { sign: entry.kind !== 'TRANSFER' ? true : undefined })}</span>
          <span className="truncate">{entry.description}</span>
        </p>
        <p className="truncate text-xs text-muted">{undone ? 'Undone' : `Added · ${[account, category, dayLabel(entry.occurredOn)].filter(Boolean).join(' · ')}`}</p>
      </div>
      {!undone && <Sure confidence={entry.confidence} />}
      {!undone && <Button size="sm" variant="ghost" onClick={() => void undo()} disabled={busy}><Undo2 />Undo</Button>}
    </div>
  )
}

function DraftCard({ entry }: { entry: QuickEntry }) {
  const space = usePersonalSpaceId()
  const accounts = useAccounts(false, space).data ?? []
  const categories = useCategories(space).data ?? []
  const client = useQueryClient()
  const [kind, setKind] = useState(entry.kind)
  const [amount, setAmount] = useState(minorToInput(entry.amount))
  const [description, setDescription] = useState(entry.description)
  const [accountId, setAccountId] = useState(entry.accountId ?? '')
  const [toAccountId, setToAccountId] = useState(entry.toAccountId ?? '')
  const [categoryId, setCategoryId] = useState(entry.categoryId ?? '')
  const [date, setDate] = useState(entry.occurredOn)
  const [state, setState] = useState<'draft' | 'saving' | 'added' | 'discarded'>('draft')
  const choices = useMemo(() => categories.filter((c) => !c.hidden && c.kind === (kind === 'INCOME' ? 'INCOME' : 'EXPENSE')), [categories, kind])
  const minor = parseMoney(amount, entry.currency) ?? 0
  const ready = minor > 0 && accountId && description.trim() && (kind !== 'TRANSFER' || (toAccountId && toAccountId !== accountId))

  async function add() {
    if (!space || !ready) return
    setState('saving')
    try {
      await api(`/api/core/spaces/${space}/transactions`, {
        method: 'POST',
        json: {
          id: crypto.randomUUID(), kind, amount: minor, accountId, toAccountId: kind === 'TRANSFER' ? toAccountId : null,
          categoryId: kind === 'TRANSFER' ? null : categoryId || null, occurredOn: date, description: description.trim().slice(0, 120),
          tags: ['ai'], counterparty: entry.counterparty,
        },
      })
      setState('added')
      void client.invalidateQueries()
    } catch {
      setState('draft')
      toast.error("Couldn't add it", { description: 'Check the details and try again.' })
    }
  }

  if (state === 'added' || state === 'discarded') {
    return (
      <div className="flex items-center gap-3 rounded-card border border-border p-3 text-sm text-muted">
        {state === 'added' ? <Check className="size-4 text-good" /> : <X className="size-4" />}
        {state === 'added' ? `Added: ${description}` : 'Left out'}
      </div>
    )
  }
  return (
    <div className="flex flex-col gap-2.5 rounded-card border border-border-strong p-3">
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs font-medium tracking-wide text-subtle uppercase">Check and add</span>
        <Sure confidence={entry.confidence} />
      </div>
      {entry.why.length > 0 && <ul className="flex flex-col gap-0.5 text-xs text-warning">{entry.why.map((w) => <li key={w}>• {w}</li>)}</ul>}
      <div className="grid grid-cols-3 gap-1 rounded-control bg-surface-2 p-0.5 text-xs" role="radiogroup" aria-label="Kind">
        {(['EXPENSE', 'INCOME', 'TRANSFER'] as const).map((k) => (
          <button key={k} type="button" role="radio" aria-checked={kind === k} onClick={() => setKind(k)}
            className={cn('rounded-[calc(var(--radius-control)-2px)] py-1.5', kind === k ? 'bg-surface font-medium shadow-sm' : 'text-muted')}>
            {k === 'EXPENSE' ? 'Spent' : k === 'INCOME' ? 'Received' : 'Transfer'}
          </button>
        ))}
      </div>
      <div className="grid grid-cols-[1fr_1.4fr] gap-2">
        <MoneyInput aria-label="Amount" value={amount} currency={entry.currency} onChange={(e) => setAmount(e.target.value)} />
        <Input aria-label="Description" value={description} maxLength={120} onChange={(e) => setDescription(e.target.value)} />
      </div>
      <div className={cn('grid gap-2', kind === 'TRANSFER' ? 'grid-cols-2' : 'grid-cols-2')}>
        <SearchSelect aria-label={kind === 'TRANSFER' ? 'From account' : 'Account'} value={accountId} onChange={setAccountId}
          options={accountOptions(accounts)} placeholder={kind === 'TRANSFER' ? 'From…' : 'Account…'} />
        {kind === 'TRANSFER'
          ? <SearchSelect aria-label="To account" value={toAccountId} onChange={setToAccountId} options={accountOptions(accounts)} placeholder="To…" />
          : <SearchSelect aria-label="Category" value={categoryId} onChange={setCategoryId} options={categoryOptions(choices, categories)} placeholder="Category…" />}
      </div>
      <div className="flex items-center gap-2">
        <Input type="date" aria-label="Date" value={date} onChange={(e) => setDate(e.target.value)} className="w-auto flex-1" />
        <Button variant="ghost" size="sm" onClick={() => setState('discarded')}>Leave out</Button>
        <Button variant="primary" size="sm" disabled={!ready || state === 'saving'} onClick={() => void add()}>
          {state === 'saving' ? <Loader2 className="animate-spin" /> : <Check />}Add
        </Button>
      </div>
    </div>
  )
}
