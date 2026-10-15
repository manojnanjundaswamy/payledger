/**
 * PayLedger's chime: two soft bell notes, a fifth apart, made in the browser (no sound file to download). Each note
 * is a sine with a quiet octave and a slightly detuned shimmer, struck and left to ring out, like a small glass bell.
 * Played for new notifications while the app is open; never more than once in a few seconds.
 */
let context: AudioContext | null = null
let last = 0

export function playChime() {
  const now = Date.now()
  if (now - last < 2500) return
  last = now
  try {
    const AC = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
    if (!AC) return
    context ??= new AC()
    const ctx = context
    // Browsers start audio suspended until someone has touched the page; then this just stays quiet.
    if (ctx.state === 'suspended') void ctx.resume()
    const master = ctx.createGain()
    master.gain.value = 0.16
    master.connect(ctx.destination)
    bell(ctx, master, 1318.5, ctx.currentTime)        // E6
    bell(ctx, master, 1975.5, ctx.currentTime + 0.12) // B6
  } catch {
    // No sound is fine.
  }
}

function bell(ctx: AudioContext, out: AudioNode, frequency: number, at: number) {
  for (const [ratio, level, decay] of [[1, 1, 1.6], [2, 0.18, 0.9], [1.003, 0.35, 1.3]] as const) {
    const osc = ctx.createOscillator()
    const gain = ctx.createGain()
    osc.type = 'sine'
    osc.frequency.value = frequency * ratio
    gain.gain.setValueAtTime(0.0001, at)
    gain.gain.exponentialRampToValueAtTime(level, at + 0.008)
    gain.gain.exponentialRampToValueAtTime(0.0001, at + decay)
    osc.connect(gain).connect(out)
    osc.start(at)
    osc.stop(at + decay + 0.05)
  }
}
