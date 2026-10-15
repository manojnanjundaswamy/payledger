import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate, useRouterState } from '@tanstack/react-router'
import { useQueryClient } from '@tanstack/react-query'
import { AnimatePresence, motion } from 'motion/react'
import { AlertTriangle, ArrowLeftRight, AudioLines, Check, CircleDollarSign, Goal, Keyboard, Loader2, Mic, MicOff, PiggyBank, Receipt, Send, Undo2, Wallet, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useDictation } from '@/hooks/useDictation'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/cn'
import { speak, stopSpeaking } from '@/lib/speak'
import { useAiStatus } from '@/state/ai'
import { usePersonalSpaceId } from '@/state/money'
import { useProfile } from '@/state/profile'
import { askAssistant, localIntent, runProposal, type Proposal, type Turn, type UndoStep } from '@/state/voice'

const TOGGLE = 'payledger:voice-mode'

/** Turns voice mode on or off from anywhere (the top bar, quick add). */
export function toggleVoiceMode(on?: boolean) {
  window.dispatchEvent(new CustomEvent(TOGGLE, { detail: { on } }))
}

type Phase = 'listening' | 'thinking' | 'speaking' | 'running' | 'paused'
type Run = 'waiting' | 'running' | 'done' | 'failed'

const ICON: Record<Proposal['type'], typeof Wallet> = { TRANSACTION: Wallet, BUDGET: PiggyBank, BILL: Receipt, PAY_BILL: Check, GOAL: Goal }

/**
 * Voice mode: talk to PayLedger. Say one thing or ten ("food budget 8,000, rent 20,000, and 450 on petrol yesterday");
 * each becomes a card, read back in a sentence; "add them" saves them through the app's own forms' API, "make the
 * petrol one 500" changes one, "undo that" takes them back. The app stays usable behind it and moves to where things
 * happen. Voice first; typing works too.
 */
export function VoiceMode() {
  const [on, setOn] = useState(false)
  useEffect(() => {
    const t = (e: Event) => setOn((v) => (e as CustomEvent<{ on?: boolean }>).detail?.on ?? !v)
    window.addEventListener(TOGGLE, t)
    return () => window.removeEventListener(TOGGLE, t)
  }, [])
  return <AnimatePresence>{on && <Assistant key="voice" onClose={() => toggleVoiceMode(false)} />}</AnimatePresence>
}

function Assistant({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate()
  const pathname = useRouterState({ select: (r) => r.location.pathname })
  const client = useQueryClient()
  const space = usePersonalSpaceId()
  const status = useAiStatus()
  const locale = useProfile().data?.settings.locale ?? 'en-IN'
  const [phase, setPhase] = useState<Phase>('paused')
  const [turns, setTurns] = useState<Turn[]>([])
  const [proposals, setProposals] = useState<Proposal[]>([])
  const [runs, setRuns] = useState<Record<string, Run>>({})
  const [caption, setCaption] = useState('')
  const [typing, setTyping] = useState(false)
  const [draft, setDraft] = useState('')
  const [muted, setMuted] = useState(false)
  const undo = useRef<UndoStep[]>([])
  const alive = useRef(true)
  const state = useRef({ turns, proposals, pathname, muted })
  state.current = { turns, proposals, pathname, muted }
  const scroller = useRef<HTMLDivElement>(null)

  const dictation = useDictation({
    lang: locale,
    serverCanTranscribe: !!status.data?.speechToText,
    silenceStopMs: 45_000,
    onText: (c, i) => setCaption(`${c} ${i}`.trim()),
    onUtterance: (text) => { setCaption(''); void handle(text, 'VOICE') },
  })

  const listen = useCallback(() => {
    if (!alive.current || state.current.muted) { setPhase('paused'); return }
    if (dictation.mode === 'none') { setPhase('paused'); setTyping(true); return }
    setPhase('listening')
    void dictation.start('')
  }, [dictation])

  /** Says it (and shows it), then listens again. */
  const reply = useCallback(async (text: string) => {
    if (!text) { listen(); return }
    setTurns((t) => [...t, { role: 'assistant', text }])
    setPhase('speaking')
    await speak(text, locale)
    if (alive.current) listen()
  }, [listen, locale])

  // Start listening as soon as it opens; stop everything when it closes.
  useEffect(() => {
    alive.current = true
    // Shown, not spoken: listening starts at once.
    setTurns([{ role: 'assistant', text: 'I’m listening. Tell me what to add, set or find, as many as you like.' }])
    const t = window.setTimeout(listen, 200)
    return () => { alive.current = false; window.clearTimeout(t); stopSpeaking(); dictation.stop() }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => { scroller.current?.scrollTo({ top: scroller.current.scrollHeight, behavior: 'smooth' }) }, [turns, proposals, caption])

  async function confirm() {
    const ready = state.current.proposals.filter((p) => p.ready)
    if (!space || !ready.length) {
      await reply(state.current.proposals.length ? 'Those still need an answer first.' : 'There’s nothing waiting to save.')
      return
    }
    setPhase('running')
    const steps: UndoStep[] = []
    let saved = 0
    let lastPage = ''
    for (const p of ready) {
      setRuns((r) => ({ ...r, [p.id]: 'running' }))
      try {
        steps.push(await runProposal(space, p))
        saved++
        lastPage = p.page
        setRuns((r) => ({ ...r, [p.id]: 'done' }))
      } catch {
        setRuns((r) => ({ ...r, [p.id]: 'failed' }))
      }
    }
    undo.current = steps
    void client.invalidateQueries()
    if (lastPage && lastPage !== state.current.pathname) void navigate({ to: lastPage })
    // The saved cards fade out after a moment; what still needs an answer stays.
    window.setTimeout(() => {
      const left = state.current.proposals.filter((p) => !ready.includes(p))
      state.current.proposals = left
      setProposals(left)
    }, 1400)
    const left = state.current.proposals.length - ready.length
    const failed = ready.length - saved
    await reply(`Saved ${saved === 1 ? 'it' : saved}.` + (failed ? ` ${failed} didn’t go through.` : '') + (left ? ` ${left} still need${left === 1 ? 's' : ''} an answer.` : ' Say undo if that’s wrong.'))
  }

  async function act(intent: 'CONFIRM' | 'CANCEL' | 'UNDO' | 'STOP') {
    if (intent === 'STOP') { onClose(); return }
    if (intent === 'CANCEL') {
      state.current.proposals = []
      setProposals([])
      await reply('Okay, cleared.')
      return
    }
    if (intent === 'UNDO') {
      if (!undo.current.length) { await reply('There’s nothing to undo.'); return }
      setPhase('running')
      for (const step of [...undo.current].reverse()) await step().catch(() => undefined)
      undo.current = []
      void client.invalidateQueries()
      await reply('Undone.')
      return
    }
    await confirm()
  }

  async function handle(text: string, channel: 'VOICE' | 'TYPED') {
    const said = text.trim()
    if (!said) { listen(); return }
    setTurns((t) => [...t, { role: 'person', text: said }])
    // "Yes", "cancel", "undo", "stop": answered right here, instantly, no AI.
    const quick = localIntent(said)
    if (quick && (quick !== 'CONFIRM' || state.current.proposals.length)) { await act(quick); return }
    setPhase('thinking')
    try {
      const r = await askAssistant([...state.current.turns, { role: 'person', text: said }], state.current.proposals, state.current.pathname, channel)
      if (!alive.current) return
      if (r.proposals.length) {
        // Merged here, not in a state updater: a "yes" in the same breath must see the new list at once.
        const next = [...state.current.proposals]
        for (const p of r.proposals) {
          // The model says which card it corrects; if it forgets, the same kind, name and amount is the same card.
          const same = next.findIndex((x) => x.type === p.type && x.title === p.title && (x.amount === p.amount || !x.ready))
          const at = p.replaces != null && next[p.replaces] ? p.replaces : same
          if (at >= 0) next[at] = p
          else next.push(p)
        }
        state.current.proposals = next
        setProposals(next)
      }
      if (r.navigate && r.navigate !== state.current.pathname) void navigate({ to: r.navigate })
      if (r.intent) {
        if (r.say && r.intent !== 'CONFIRM') setTurns((t) => [...t, { role: 'assistant', text: r.say }])
        await act(r.intent)
        return
      }
      await reply(r.say || 'Okay.')
    } catch (e) {
      const msg = e instanceof ApiError && e.code === 'plan-limit' ? 'You’ve used this month’s AI on Free. You can still add by hand, or go Premium.'
        : e instanceof ApiError && e.code === 'ai-off' ? 'Voice mode needs AI, and it isn’t switched on here yet.'
        : e instanceof ApiError && e.code === 'ai-busy' ? 'That was a lot at once. Give me a moment and try again.'
        : 'I couldn’t catch that just now. Try again?'
      await reply(msg)
    }
  }

  const hearing = phase === 'listening' && dictation.hearing
  const edge = phase === 'listening' ? (hearing ? 'hearing' : 'listening') : phase === 'running' ? 'thinking' : phase === 'paused' ? 'idle' : phase
  const label = { listening: hearing ? 'Hearing you…' : 'Listening', thinking: 'Thinking…', speaking: 'Speaking', running: 'Saving…', paused: muted ? 'Paused' : 'Ready' }[phase]
  const ready = proposals.filter((p) => p.ready && runs[p.id] !== 'done').length
  const recent = turns.slice(-6)

  return (
    <>
      <motion.div className="voice-edge" data-state={edge} initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: 0.35 }} aria-hidden>
        <div className="halo" />
        <div className="ring" />
      </motion.div>
      <motion.section role="dialog" aria-label="Voice mode" aria-modal={false}
        initial={{ opacity: 0, y: 24, scale: 0.98 }} animate={{ opacity: 1, y: 0, scale: 1 }} exit={{ opacity: 0, y: 24, scale: 0.98 }}
        transition={{ type: 'spring', stiffness: 380, damping: 32 }}
        className="fixed inset-x-2 bottom-2 z-[95] flex max-h-[64dvh] flex-col overflow-hidden rounded-[26px] border border-white/10 bg-surface/75 shadow-[0_24px_80px_-20px_rgba(0,0,0,0.55)] backdrop-blur-2xl md:inset-x-auto md:top-20 md:right-4 md:bottom-4 md:max-h-none md:w-[400px]">
        <header className="flex items-center gap-3 px-4 pt-3.5 pb-2">
          <Orb phase={phase} hearing={hearing} size="sm" />
          <div className="min-w-0 flex-1">
            <p className="text-sm font-semibold">Voice mode</p>
            <p className="text-xs text-muted" aria-live="polite">{label}</p>
          </div>
          <Button variant="ghost" size="icon-sm" aria-label={typing ? 'Hide the keyboard' : 'Type instead'} onClick={() => setTyping((v) => !v)}><Keyboard /></Button>
          <Button variant="ghost" size="icon-sm" aria-label="Close voice mode" onClick={onClose}><X /></Button>
        </header>

        <div ref={scroller} className="flex min-h-24 flex-1 flex-col gap-3 overflow-y-auto px-4 pb-3">
          <AnimatePresence initial={false}>
            {recent.map((t, i) => (
              <motion.p key={turns.length - recent.length + i} layout initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }}
                className={cn('max-w-[90%] text-[0.9375rem] leading-relaxed', t.role === 'person'
                  ? 'self-end rounded-2xl rounded-br-md bg-accent/12 px-3 py-2 text-fg'
                  : 'self-start text-fg/90')}>
                {t.text}
              </motion.p>
            ))}
          </AnimatePresence>
          {caption && (
            <p className="self-end text-right text-lg leading-snug font-medium text-fg/80">
              {caption}<span aria-hidden className="ml-0.5 inline-block h-5 w-0.5 translate-y-1 animate-pulse bg-accent" />
            </p>
          )}
          {phase === 'thinking' && <Thinking />}
          <AnimatePresence>
            {proposals.map((p, i) => (
              <ProposalCard key={p.id} p={p} index={i} run={runs[p.id]} onRemove={() => setProposals((ps) => ps.filter((x) => x.id !== p.id))} />
            ))}
          </AnimatePresence>
        </div>

        {ready > 0 && phase !== 'running' && (
          <div className="flex items-center gap-2 border-t border-border/60 px-4 py-2.5">
            <Button variant="primary" className="flex-1" onClick={() => { dictation.stop(); stopSpeaking(); void act('CONFIRM') }}>
              <Check />{ready === 1 ? 'Save it' : `Save all ${ready}`}
            </Button>
            <Button variant="ghost" onClick={() => { dictation.stop(); stopSpeaking(); void act('CANCEL') }}>Clear</Button>
            <span className="hidden text-xs text-subtle sm:inline">or say “add them”</span>
          </div>
        )}
        {undo.current.length > 0 && !proposals.length && phase !== 'running' && (
          <div className="flex justify-center border-t border-border/60 px-4 py-2">
            <Button variant="ghost" size="sm" onClick={() => { dictation.stop(); stopSpeaking(); void act('UNDO') }}><Undo2 />Undo what was just saved</Button>
          </div>
        )}

        <footer className="flex items-center gap-3 border-t border-border/60 px-4 py-3">
          {typing ? (
            <form className="flex flex-1 items-center gap-2" onSubmit={(e) => { e.preventDefault(); const t = draft; setDraft(''); dictation.stop(); stopSpeaking(); void handle(t, 'TYPED') }}>
              <input autoFocus value={draft} onChange={(e) => setDraft(e.target.value)} aria-label="Type to PayLedger" placeholder="Type, or tap the mic"
                className="h-10 min-w-0 flex-1 rounded-full border border-border bg-surface-2 px-4 text-sm outline-none focus:border-border-strong" />
              <Button type="submit" variant="primary" size="icon" aria-label="Send" disabled={!draft.trim()}><Send /></Button>
            </form>
          ) : (
            <p className="flex-1 text-xs text-subtle">
              {phase === 'paused' ? 'Tap the orb to talk.' : 'Pause when you’re done; “add them” saves, “undo” takes back, “stop” closes.'}
            </p>
          )}
          <button type="button" onClick={() => {
            if (phase === 'listening') { setMuted(true); dictation.stop(); setPhase('paused') }
            else { setMuted(false); state.current.muted = false; stopSpeaking(); listen() }
          }}
            aria-label={phase === 'listening' ? 'Pause listening' : 'Listen'} disabled={dictation.mode === 'none'}
            className="relative shrink-0 rounded-full disabled:opacity-40">
            <Orb phase={phase} hearing={hearing} size="lg" />
            <span className="absolute inset-0 flex items-center justify-center text-white">
              {phase === 'listening' ? <Mic className="size-5" /> : phase === 'thinking' || phase === 'running' ? <Loader2 className="size-5 animate-spin" /> : phase === 'paused' ? <MicOff className="size-5" /> : <Mic className="size-5 opacity-70" />}
            </span>
          </button>
        </footer>
        {status.data?.usage.limit != null && (
          <p className="pb-2 text-center text-[0.6875rem] text-subtle">{Math.max(0, status.data.usage.limit - status.data.usage.used)} of {status.data.usage.limit} AI reads left this month</p>
        )}
      </motion.section>
    </>
  )
}

/** A living ball of colour: still when paused, breathing when listening, quick when it hears you, turning while it thinks. */
function Orb({ phase, hearing, size }: { phase: Phase; hearing: boolean; size: 'sm' | 'lg' }) {
  const px = size === 'lg' ? 52 : 30
  return (
    <motion.span aria-hidden className="relative block rounded-full"
      style={{ width: px, height: px, background: 'conic-gradient(from 0deg, var(--accent), #8b5cf6, #22d3ee, #f472b6, var(--accent))' }}
      animate={{
        rotate: phase === 'thinking' || phase === 'running' ? 360 : 0,
        scale: phase === 'paused' ? 0.92 : hearing ? [1, 1.12, 0.98, 1.08, 1] : phase === 'speaking' ? [1, 1.06, 1] : [1, 1.04, 1],
        opacity: phase === 'paused' ? 0.55 : 1,
      }}
      transition={{
        rotate: phase === 'thinking' || phase === 'running' ? { duration: 1.1, repeat: Infinity, ease: 'linear' } : { duration: 0.4 },
        scale: { duration: hearing ? 1.05 : phase === 'speaking' ? 0.7 : 2.6, repeat: Infinity, ease: 'easeInOut' },
      }}>
      <span className="absolute inset-[18%] rounded-full bg-white/25 blur-[6px]" />
    </motion.span>
  )
}

function Thinking() {
  return (
    <div className="flex items-center gap-1.5 self-start py-1" aria-label="Thinking">
      {[0, 1, 2].map((i) => (
        <motion.span key={i} className="size-2 rounded-full bg-accent" animate={{ opacity: [0.25, 1, 0.25], y: [0, -3, 0] }}
          transition={{ duration: 0.9, repeat: Infinity, delay: i * 0.15 }} />
      ))}
    </div>
  )
}

function ProposalCard({ p, index, run, onRemove }: { p: Proposal; index: number; run?: Run; onRemove: () => void }) {
  const Icon = p.type === 'TRANSACTION' && p.title.startsWith('Moved') ? ArrowLeftRight : p.type === 'TRANSACTION' && p.title.startsWith('Received') ? CircleDollarSign : ICON[p.type]
  const pct = Math.round(p.confidence * 100)
  return (
    <motion.div layout initial={{ opacity: 0, y: 10, scale: 0.98 }} animate={{ opacity: run === 'done' ? 0.6 : 1, y: 0, scale: 1 }} exit={{ opacity: 0, x: 40 }}
      transition={{ type: 'spring', stiffness: 420, damping: 34, delay: run ? 0 : index * 0.05 }}
      className={cn('flex items-start gap-3 rounded-2xl border p-3', !p.ready ? 'border-warning/40 bg-warning/5' : run === 'done' ? 'border-good/40 bg-good/5' : 'border-border bg-surface/80')}>
      <span className={cn('flex size-9 shrink-0 items-center justify-center rounded-xl', run === 'done' ? 'bg-good text-white' : 'bg-surface-2 text-fg')}>
        {run === 'running' ? <Loader2 className="size-4 animate-spin" /> : run === 'done' ? <Check className="size-4" /> : run === 'failed' ? <AlertTriangle className="size-4 text-critical" /> : <Icon className="size-4" />}
      </span>
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium">{p.title}</p>
        <p className="text-xs text-muted">{p.summary}</p>
        {!p.ready && p.why.length > 0 && <p className="mt-1 text-xs text-warning">{p.why.join(' ')}</p>}
        {run === 'failed' && <p className="mt-1 text-xs text-critical">Didn’t go through. Check it on its page.</p>}
      </div>
      <div className="flex shrink-0 flex-col items-end gap-1">
        <span className={cn('text-[0.6875rem] tabular', pct >= 95 ? 'text-good-text' : pct >= 75 ? 'text-warning' : 'text-subtle')} title="How sure every detail is right">{pct}%</span>
        {!run && <button type="button" onClick={onRemove} aria-label={`Remove ${p.title}`} className="rounded-full p-0.5 text-subtle hover:bg-surface-2 hover:text-fg"><X className="size-3.5" /></button>}
      </div>
    </motion.div>
  )
}

/** The switch in the top bar: voice mode on or off, wherever you are. */
export function VoiceModeToggle() {
  const [on, setOn] = useState(false)
  useEffect(() => {
    const t = (e: Event) => setOn((v) => (e as CustomEvent<{ on?: boolean }>).detail?.on ?? !v)
    window.addEventListener(TOGGLE, t)
    return () => window.removeEventListener(TOGGLE, t)
  }, [])
  return (
    <button type="button" role="switch" aria-checked={on} aria-label="Voice mode" onClick={() => toggleVoiceMode(!on)} data-tour="voice"
      className={cn('group flex h-9 items-center gap-2 rounded-full border px-2 text-sm transition-colors',
        on ? 'border-transparent bg-accent text-accent-fg' : 'border-border bg-surface-2 text-muted hover:border-border-strong hover:text-fg')}>
      <span className="relative flex size-5 items-center justify-center">
        {on && <span className="absolute inset-0 animate-ping rounded-full bg-accent-fg/30" />}
        <AudioLines className="size-4" />
      </span>
      <span className="hidden pr-1 font-medium sm:inline">Voice</span>
      <span aria-hidden className={cn('hidden h-4 w-7 items-center rounded-full p-0.5 transition-colors lg:flex', on ? 'bg-accent-fg/40' : 'bg-border-strong')}>
        <span className={cn('size-3 rounded-full bg-white shadow transition-transform', on && 'translate-x-3')} />
      </span>
    </button>
  )
}
