/** WCAG 2.x contrast helpers, used to keep a user-chosen accent readable in both themes. */

export function parseHex(hex: string): [number, number, number] {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim())
  if (!m) throw new Error(`not a #rrggbb colour: ${hex}`)
  const n = parseInt(m[1]!, 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}

export function toHex([r, g, b]: [number, number, number]): string {
  return '#' + [r, g, b].map((c) => Math.round(Math.min(255, Math.max(0, c))).toString(16).padStart(2, '0')).join('')
}

export function luminance(hex: string): number {
  const [r, g, b] = parseHex(hex).map((c) => {
    const s = c / 255
    return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  }) as [number, number, number]
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

export function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number]
  return (hi + 0.05) / (lo + 0.05)
}

/** Black or white, whichever reads better on the given background. */
export function readableOn(bg: string): '#0a0a0a' | '#ffffff' {
  return contrast(bg, '#0a0a0a') >= contrast(bg, '#ffffff') ? '#0a0a0a' : '#ffffff'
}

function mix(hex: string, toward: string, t: number): string {
  const a = parseHex(hex)
  const b = parseHex(toward)
  return toHex([a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t])
}

/**
 * Nudges the accent toward black (on light) or white (on dark) until it reaches 3:1 against the
 * page, the minimum for UI components (WCAG 1.4.11). A pale yellow stays recognisably yellow,
 * just deep enough to see.
 */
export function accentFor(hex: string, page: string): string {
  const toward = luminance(page) > 0.5 ? '#000000' : '#ffffff'
  let candidate = hex
  for (let t = 0.05; contrast(candidate, page) < 3 && t <= 1; t += 0.05) {
    candidate = mix(hex, toward, t)
  }
  return candidate
}
