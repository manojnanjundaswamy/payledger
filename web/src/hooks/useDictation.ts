import { useCallback, useEffect, useRef, useState } from 'react'
import { transcribe } from '@/state/ai'

/**
 * Speak instead of typing, in any browser:
 * - "live": the browser's own speech recognition (Chrome, Edge, Safari, Samsung Internet), free, no key. Words appear
 *   as you speak.
 * - "record": no recognition in this browser (Firefox) or it fails (Brave): we record, and the server turns the
 *   recording into text when you stop (if the site has speech to text set up).
 * - "none": neither is possible; type instead.
 *
 * Browsers end a recognition session after a short silence (Android after a second or two), so a session that ends
 * while you're still dictating is quietly started again: it keeps listening through pauses until you tap stop, or
 * after {@link SILENCE_STOP_MS} of silence.
 */
export type DictationMode = 'live' | 'record' | 'none'
export type DictationState = 'idle' | 'listening' | 'transcribing'

interface Result { isFinal: boolean; 0: { transcript: string } }
interface SpeechRecognitionLike {
  lang: string
  continuous: boolean
  interimResults: boolean
  maxAlternatives: number
  onresult: ((e: { resultIndex: number; results: ArrayLike<Result> }) => void) | null
  onerror: ((e: { error: string }) => void) | null
  onend: (() => void) | null
  onspeechstart: (() => void) | null
  onspeechend: (() => void) | null
  start(): void
  stop(): void
  abort(): void
}

function recognitionClass(): (new () => SpeechRecognitionLike) | null {
  if (typeof window === 'undefined') return null
  const w = window as unknown as { SpeechRecognition?: new () => SpeechRecognitionLike; webkitSpeechRecognition?: new () => SpeechRecognitionLike }
  return w.SpeechRecognition ?? w.webkitSpeechRecognition ?? null
}

function canRecord() {
  return typeof window !== 'undefined' && typeof window.MediaRecorder !== 'undefined' && !!navigator.mediaDevices?.getUserMedia
}

/** Android's recognizer ignores "continuous" and repeats earlier words in later results: one phrase per session there. */
const ANDROID = typeof navigator !== 'undefined' && /Android/i.test(navigator.userAgent)
/** Longest a single dictation runs: an entry, not a speech. */
const MAX_MS = 90_000
/** Silence this long ends it, as if you'd tapped stop. */
const SILENCE_STOP_MS = 10_000

const tidy = (s: string) => s.replace(/\s+/g, ' ').trim()

export function useDictation({ lang, serverCanTranscribe, onText }: {
  /** BCP 47, like en-IN or hi-IN */
  lang: string
  serverCanTranscribe: boolean
  /** Everything said so far: {@code committed} is settled, {@code interim} is still being heard (may change). */
  onText: (committed: string, interim: string) => void
}) {
  const [liveBroken, setLiveBroken] = useState(false)
  const mode: DictationMode = recognitionClass() && !liveBroken ? 'live' : canRecord() && serverCanTranscribe ? 'record' : 'none'
  const [state, setState] = useState<DictationState>('idle')
  const [error, setError] = useState<string | null>(null)
  /** Speech is being heard right now (live), or the input level (record): drives the waveform. */
  const [hearing, setHearing] = useState(false)
  const [level, setLevel] = useState(0)
  const [elapsed, setElapsed] = useState(0)

  const onTextRef = useRef(onText)
  onTextRef.current = onText
  const active = useRef(false)
  const committed = useRef('')
  const began = useRef(0)
  const lastSpeech = useRef(0)
  const recognition = useRef<SpeechRecognitionLike | null>(null)
  const recorder = useRef<MediaRecorder | null>(null)
  const stream = useRef<MediaStream | null>(null)
  const meter = useRef<{ ctx: AudioContext; raf: number } | null>(null)
  const clock = useRef<number | null>(null)

  const stopClock = () => {
    if (clock.current) window.clearInterval(clock.current)
    clock.current = null
  }

  const stopMeter = useCallback(() => {
    if (meter.current) {
      cancelAnimationFrame(meter.current.raf)
      void meter.current.ctx.close().catch(() => undefined)
      meter.current = null
    }
    stream.current?.getTracks().forEach((t) => t.stop())
    stream.current = null
    setLevel(0)
  }, [])

  const finish = useCallback(() => {
    active.current = false
    stopClock()
    setHearing(false)
    setState((s) => (s === 'listening' ? 'idle' : s))
  }, [])

  const stop = useCallback(() => {
    active.current = false
    stopClock()
    recognition.current?.stop()
    if (recorder.current && recorder.current.state !== 'inactive') recorder.current.stop()
    else stopMeter()
    setHearing(false)
  }, [stopMeter])

  /** One recognition session; when it ends while still dictating, the next one starts with what was said kept. */
  const session = useCallback(() => {
    const R = recognitionClass()!
    const r = new R()
    r.lang = lang
    r.continuous = !ANDROID
    r.interimResults = true
    r.maxAlternatives = 1
    let heard = ''
    let heardFinal = ''
    r.onresult = (e) => {
      // Rebuilt from all of this session's results each time: robust to browsers that resend earlier ones.
      let finals = ''
      let interim = ''
      for (let i = 0; i < e.results.length; i++) {
        const res = e.results[i]!
        if (res.isFinal) finals += ' ' + res[0].transcript
        else interim += ' ' + res[0].transcript
      }
      // Android may repeat the whole phrase in its last final result: keep the longest final reading.
      heardFinal = ANDROID ? tidy(e.results.length ? Array.from(e.results).filter((x) => x.isFinal).map((x) => x[0].transcript).sort((a, b) => b.length - a.length)[0] ?? '' : '') : tidy(finals)
      heard = tidy(heardFinal + ' ' + interim)
      lastSpeech.current = Date.now()
      onTextRef.current(committed.current, heard)
    }
    r.onspeechstart = () => { setHearing(true); lastSpeech.current = Date.now() }
    r.onspeechend = () => setHearing(false)
    r.onerror = (e) => {
      if (e.error === 'not-allowed' || e.error === 'service-not-allowed') {
        active.current = false
        setError('Allow the microphone for this site to speak an entry (the lock icon by the address).')
      } else if (e.error === 'network' || e.error === 'language-not-supported') {
        active.current = false
        // The API is there but can't work here (Brave, some Chromium builds): record instead, if we can.
        setLiveBroken(true)
        setError(canRecord() && serverCanTranscribe ? 'Tap the mic again: this browser records instead.' : "This browser can't take dictation. Type it instead.")
      } else if (e.error === 'audio-capture') {
        active.current = false
        setError('No microphone found.')
      }
      // no-speech and aborted: a pause; the session ends and starts again below.
    }
    r.onend = () => {
      // What this session heard is settled, even the last words that never became "final".
      committed.current = tidy(committed.current + ' ' + (heard || heardFinal))
      onTextRef.current(committed.current, '')
      recognition.current = null
      const now = Date.now()
      if (active.current && now - began.current < MAX_MS && now - lastSpeech.current < SILENCE_STOP_MS) {
        try { session() } catch { finish() }
      } else {
        finish()
      }
    }
    recognition.current = r
    r.start()
  }, [lang, serverCanTranscribe, finish])

  /** @param existing text already in the box: what's said is added after it */
  const start = useCallback(async (existing = '') => {
    if (active.current) return
    setError(null)
    committed.current = tidy(existing)
    began.current = Date.now()
    lastSpeech.current = Date.now()
    setElapsed(0)
    stopClock()
    clock.current = window.setInterval(() => {
      const ms = Date.now() - began.current
      setElapsed(Math.floor(ms / 1000))
      if (ms > MAX_MS || (mode === 'live' && Date.now() - lastSpeech.current > SILENCE_STOP_MS + 2000)) stop()
    }, 250)
    if (mode === 'live') {
      active.current = true
      setState('listening')
      try {
        session()
      } catch {
        finish()
        setError("Couldn't start listening. Try again.")
      }
      return
    }
    if (mode === 'record') {
      let s: MediaStream
      try {
        s = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } })
      } catch {
        stopClock()
        setError('Allow the microphone for this site to speak an entry (the lock icon by the address).')
        return
      }
      stream.current = s
      try {
        const ctx = new AudioContext()
        const analyser = ctx.createAnalyser()
        analyser.fftSize = 256
        ctx.createMediaStreamSource(s).connect(analyser)
        const data = new Uint8Array(analyser.frequencyBinCount)
        const tick = () => {
          analyser.getByteTimeDomainData(data)
          let peak = 0
          for (const v of data) peak = Math.max(peak, Math.abs(v - 128))
          const l = Math.min(1, peak / 48)
          setLevel(l)
          setHearing(l > 0.12)
          if (meter.current) meter.current.raf = requestAnimationFrame(tick)
        }
        meter.current = { ctx, raf: requestAnimationFrame(tick) }
      } catch { /* no level meter: recording still works */ }
      const type = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg'].find((t) => MediaRecorder.isTypeSupported?.(t))
      const rec = new MediaRecorder(s, type ? { mimeType: type } : undefined)
      const chunks: Blob[] = []
      const t0 = performance.now()
      rec.ondataavailable = (e) => { if (e.data.size) chunks.push(e.data) }
      rec.onstop = async () => {
        stopMeter()
        recorder.current = null
        active.current = false
        const seconds = (performance.now() - t0) / 1000
        if (seconds < 0.6) { setState('idle'); return }
        setState('transcribing')
        try {
          const text = await transcribe(new Blob(chunks, { type: rec.mimeType }), seconds, lang.split('-')[0])
          committed.current = tidy(committed.current + ' ' + text)
          onTextRef.current(committed.current, '')
          if (!text) setError("Didn't catch that. Try again.")
        } catch {
          setError("Couldn't turn that into text just now. Type it instead.")
        } finally {
          setState('idle')
        }
      }
      recorder.current = rec
      active.current = true
      rec.start()
      setState('listening')
    }
  }, [lang, mode, session, stop, stopMeter, finish])

  // Leaving the page stops the mic.
  useEffect(() => () => {
    active.current = false
    stopClock()
    recognition.current?.abort()
    if (recorder.current && recorder.current.state !== 'inactive') recorder.current.stop()
    stopMeter()
  }, [stopMeter])

  return { mode, state, error, hearing, level, elapsed, start, stop }
}
