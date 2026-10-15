import { useCallback, useEffect, useRef, useState } from 'react'
import { transcribe } from '@/state/ai'

/**
 * Speak instead of typing, in any browser:
 * - "live": the browser's own speech recognition (Chrome, Edge, Safari, Samsung Internet): words appear as you speak,
 *   on the device, free.
 * - "record": no recognition in this browser (Firefox) or it fails (Brave): we record, and the server turns the
 *   recording into text when you stop (if the site has speech to text set up).
 * - "none": neither is possible; type instead.
 */
export type DictationMode = 'live' | 'record' | 'none'
export type DictationState = 'idle' | 'listening' | 'transcribing'

interface SpeechRecognitionLike {
  lang: string
  continuous: boolean
  interimResults: boolean
  onresult: ((e: { resultIndex: number; results: ArrayLike<{ isFinal: boolean; 0: { transcript: string } }> }) => void) | null
  onerror: ((e: { error: string }) => void) | null
  onend: (() => void) | null
  start(): void
  stop(): void
}

function recognitionClass(): (new () => SpeechRecognitionLike) | null {
  const w = window as unknown as { SpeechRecognition?: new () => SpeechRecognitionLike; webkitSpeechRecognition?: new () => SpeechRecognitionLike }
  return w.SpeechRecognition ?? w.webkitSpeechRecognition ?? null
}

function canRecord() {
  return typeof window !== 'undefined' && typeof window.MediaRecorder !== 'undefined' && !!navigator.mediaDevices?.getUserMedia
}

/** Longest a single dictation runs: an entry, not a speech. */
const MAX_SECONDS = 60

export function useDictation({ lang, serverCanTranscribe, onText }: {
  /** BCP 47, like en-IN or hi-IN */
  lang: string
  serverCanTranscribe: boolean
  /** The words so far (final + what's being heard now), each time they change. */
  onText: (text: string) => void
}) {
  const [liveBroken, setLiveBroken] = useState(false)
  const mode: DictationMode = typeof window === 'undefined' ? 'none'
    : recognitionClass() && !liveBroken ? 'live'
    : canRecord() && serverCanTranscribe ? 'record' : 'none'
  const [state, setState] = useState<DictationState>('idle')
  const [error, setError] = useState<string | null>(null)
  /** 0..1, how loud: the mic button breathes with your voice. */
  const [level, setLevel] = useState(0)
  const recognition = useRef<SpeechRecognitionLike | null>(null)
  const recorder = useRef<MediaRecorder | null>(null)
  const stream = useRef<MediaStream | null>(null)
  const meter = useRef<{ ctx: AudioContext; raf: number } | null>(null)
  const timer = useRef<number | null>(null)
  const base = useRef('')
  const onTextRef = useRef(onText)
  onTextRef.current = onText

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

  const startMeter = useCallback(async () => {
    const s = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } })
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
        setLevel(Math.min(1, peak / 64))
        if (meter.current) meter.current.raf = requestAnimationFrame(tick)
      }
      meter.current = { ctx, raf: requestAnimationFrame(tick) }
    } catch { /* no level meter: dictation still works */ }
    return s
  }, [])

  const stop = useCallback(() => {
    if (timer.current) window.clearTimeout(timer.current)
    timer.current = null
    recognition.current?.stop()
    if (recorder.current && recorder.current.state !== 'inactive') recorder.current.stop()
    else stopMeter()
  }, [stopMeter])

  /** @param existing text already in the box: what's said is added after it */
  const start = useCallback(async (existing = '') => {
    setError(null)
    base.current = existing.trim() ? existing.trim() + ' ' : ''
    if (mode === 'live') {
      const R = recognitionClass()!
      const r = new R()
      r.lang = lang
      r.continuous = true
      r.interimResults = true
      let finals = ''
      r.onresult = (e) => {
        let interim = ''
        for (let i = e.resultIndex; i < e.results.length; i++) {
          const res = e.results[i]!
          if (res.isFinal) finals += res[0].transcript + ' '
          else interim += res[0].transcript
        }
        onTextRef.current((base.current + finals + interim).replace(/\s+/g, ' ').trimStart())
      }
      r.onerror = (e) => {
        if (e.error === 'not-allowed' || e.error === 'service-not-allowed') setError('Allow the microphone for this site to speak an entry.')
        else if (e.error === 'network' || e.error === 'language-not-supported') {
          // This browser has the API but can't use it (Brave, some Chromium builds): record instead, if we can.
          setLiveBroken(true)
          setError(canRecord() && serverCanTranscribe ? 'Tap the mic again: this browser records instead.' : "This browser can't take dictation. Type it instead.")
        } else if (e.error !== 'no-speech' && e.error !== 'aborted') setError("Didn't catch that. Try again.")
      }
      r.onend = () => { stopMeter(); setState('idle'); recognition.current = null }
      recognition.current = r
      try {
        r.start()
        setState('listening')
        void startMeter().catch(() => undefined)
      } catch {
        setError("Couldn't start listening. Try again.")
      }
    } else if (mode === 'record') {
      let s: MediaStream
      try {
        s = await startMeter()
      } catch {
        setError('Allow the microphone for this site to speak an entry.')
        return
      }
      const type = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg'].find((t) => MediaRecorder.isTypeSupported?.(t))
      const rec = new MediaRecorder(s, type ? { mimeType: type } : undefined)
      const chunks: Blob[] = []
      const began = performance.now()
      rec.ondataavailable = (e) => { if (e.data.size) chunks.push(e.data) }
      rec.onstop = async () => {
        stopMeter()
        recorder.current = null
        const seconds = (performance.now() - began) / 1000
        if (seconds < 0.6) { setState('idle'); return }
        setState('transcribing')
        try {
          const text = await transcribe(new Blob(chunks, { type: rec.mimeType }), seconds, lang.split('-')[0])
          onTextRef.current((base.current + text).trim())
          if (!text) setError("Didn't catch that. Try again.")
        } catch {
          setError("Couldn't turn that into text just now. Type it instead.")
        } finally {
          setState('idle')
        }
      }
      recorder.current = rec
      rec.start()
      setState('listening')
    }
    timer.current = window.setTimeout(() => stop(), MAX_SECONDS * 1000)
  }, [lang, mode, serverCanTranscribe, startMeter, stop, stopMeter])

  // Leaving the page stops the mic.
  useEffect(() => () => {
    recognition.current?.stop()
    if (recorder.current && recorder.current.state !== 'inactive') recorder.current.stop()
    stopMeter()
  }, [stopMeter])

  return { mode, state, error, level, start, stop }
}
