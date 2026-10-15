/**
 * Speaks a short reply with the browser's own voice (free, no key): the voice that best matches the locale
 * (en-IN, hi-IN …), a little quicker than default. Resolves when it's done, or right away if speech isn't available.
 */
let chosen: SpeechSynthesisVoice | null = null

function voiceFor(locale: string): SpeechSynthesisVoice | null {
  const voices = window.speechSynthesis?.getVoices() ?? []
  if (!voices.length) return null
  const lang = locale.toLowerCase()
  const base = lang.split('-')[0] ?? lang
  const score = (v: SpeechSynthesisVoice) =>
    (v.lang.toLowerCase() === lang ? 4 : v.lang.toLowerCase().startsWith(base) ? 2 : 0)
    + (/google|natural|neural|premium|enhanced/i.test(v.name) ? 1 : 0) + (v.localService ? 0.5 : 0)
  return [...voices].sort((a, b) => score(b) - score(a))[0] ?? null
}

export function canSpeak() {
  return typeof window !== 'undefined' && 'speechSynthesis' in window
}

export function stopSpeaking() {
  if (canSpeak()) window.speechSynthesis.cancel()
}

export function speak(text: string, locale = 'en-IN'): Promise<void> {
  if (!canSpeak() || !text.trim()) return Promise.resolve()
  return new Promise((resolve) => {
    const synth = window.speechSynthesis
    synth.cancel()
    const u = new SpeechSynthesisUtterance(text)
    chosen = chosen && chosen.lang.toLowerCase().startsWith(locale.split('-')[0]!.toLowerCase()) ? chosen : voiceFor(locale)
    if (chosen) u.voice = chosen
    u.lang = chosen?.lang ?? locale
    u.rate = 1.06
    u.pitch = 1
    // Some browsers never fire "end" (or drop it): don't wait forever.
    const guard = window.setTimeout(resolve, Math.min(20_000, 1_500 + text.length * 70))
    u.onend = () => { window.clearTimeout(guard); resolve() }
    u.onerror = () => { window.clearTimeout(guard); resolve() }
    synth.speak(u)
  })
}

// Voices load late in Chrome: ask once early so the first reply has a good one.
if (canSpeak()) {
  window.speechSynthesis.getVoices()
  window.speechSynthesis.addEventListener?.('voiceschanged', () => { chosen = null })
}
