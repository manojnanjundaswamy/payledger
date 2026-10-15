import { useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { motion } from 'motion/react'

const COLORS = ['var(--accent)', 'var(--series-1)', 'var(--series-2)', 'var(--series-3)', 'var(--series-4)', 'var(--good)']

/**
 * A short burst of confetti from the top of the screen, for a badge earned. Light (36 pieces, 1.6 s), never in the
 * way (no pointer events), and skipped when someone prefers less motion.
 */
export function Confetti({ onDone }: { onDone: () => void }) {
  const [pieces] = useState(() => Array.from({ length: 36 }, (_, i) => ({
    x: Math.random() * 100, drift: (Math.random() - 0.5) * 30, rotate: Math.random() * 540 - 270, delay: Math.random() * 0.25,
    size: 6 + Math.random() * 6, color: COLORS[i % COLORS.length], round: Math.random() > 0.6,
  })))
  useEffect(() => {
    const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches
    const t = setTimeout(onDone, reduced ? 0 : 1800)
    return () => clearTimeout(t)
  }, [onDone])
  if (matchMedia('(prefers-reduced-motion: reduce)').matches) return null
  return createPortal(
    <div aria-hidden className="pointer-events-none fixed inset-0 z-[90] overflow-hidden">
      {pieces.map((p, i) => (
        <motion.span key={i} className="absolute top-0 block" style={{ left: `${p.x}%`, width: p.size, height: p.round ? p.size : p.size * 0.45,
          background: p.color, borderRadius: p.round ? '9999px' : '2px' }}
          initial={{ y: -20, x: 0, rotate: 0, opacity: 1 }}
          animate={{ y: '85vh', x: `${p.drift}vw`, rotate: p.rotate, opacity: [1, 1, 0] }}
          transition={{ duration: 1.5, delay: p.delay, ease: [0.2, 0.6, 0.4, 1] }} />
      ))}
    </div>,
    document.body,
  )
}
