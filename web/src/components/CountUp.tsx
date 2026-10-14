import { useEffect, useRef, useState } from 'react'

/**
 * A number that counts to its value (ease-out, 0.7 s) and from the old value to the new one when it changes, so a
 * figure moving is easy to follow. Instant with reduced motion.
 */
export function CountUp({ to, format, className }: { to: number; format: (v: number) => string; className?: string }) {
  const [value, setValue] = useState(to)
  const from = useRef(0)
  useEffect(() => {
    const reduced = document.documentElement.dataset.motion === 'reduced'
      || (document.documentElement.dataset.motion !== 'full' && matchMedia('(prefers-reduced-motion: reduce)').matches)
    if (reduced) {
      setValue(to)
      from.current = to
      return
    }
    const start = performance.now()
    const begin = from.current
    let frame = 0
    const tick = (now: number) => {
      const t = Math.min(1, (now - start) / 700)
      setValue(Math.round(begin + (to - begin) * (1 - (1 - t) ** 3)))
      if (t < 1) frame = requestAnimationFrame(tick)
      else from.current = to
    }
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [to])
  return <span className={className ?? 'tabular'}>{format(value)}</span>
}
