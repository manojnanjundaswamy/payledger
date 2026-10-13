import { useEffect, useRef, useState } from 'react'
import { Copy, Delete, History } from 'lucide-react'
import { toast } from 'sonner'
import { Segmented } from '@/components/ui/controls'
import { cn } from '@/lib/cn'
import { evaluate, ExprError, formatNumber, type AngleMode } from '../expr'

const STD: string[][] = [
  ['C', '(', ')', '÷'],
  ['7', '8', '9', '×'],
  ['4', '5', '6', '−'],
  ['1', '2', '3', '+'],
  ['%', '0', '.', '='],
]
const SCI: string[] = ['sin', 'cos', 'tan', 'ln', 'log', '√', 'x²', '^', 'π', 'e', '!', 'Ans', 'L', 'Cr', 'mod']
const INSERT: Record<string, string> = { '√': 'sqrt(', 'x²': '^2', sin: 'sin(', cos: 'cos(', tan: 'tan(', ln: 'ln(', log: 'log(', Ans: 'ans', L: 'L', Cr: 'cr', mod: ' mod ', π: 'π' }

/**
 * A full calculator: type or tap an expression, see the answer as you go. Brackets, powers, percentages the
 * calculator way (200 + 18% = 236), lakh and crore ("1.5L"), memory, the last answer, and a history to reuse.
 */
export function Calculator() {
  const [expr, setExpr] = useState('')
  const [mode, setMode] = useState<AngleMode>('deg')
  const [sci, setSci] = useState(false)
  const [ans, setAns] = useState(0)
  const [memory, setMemory] = useState(0)
  const [history, setHistory] = useState<{ expr: string; value: number }[]>([])
  const [error, setError] = useState<string>()
  const input = useRef<HTMLInputElement>(null)

  let preview: number | null = null
  try { preview = expr.trim() ? evaluate(expr, mode, ans) : null } catch { preview = null }

  function equals() {
    if (!expr.trim()) return
    try {
      const v = evaluate(expr, mode, ans)
      setHistory((h) => [{ expr, value: v }, ...h].slice(0, 30))
      setAns(v)
      setExpr(String(v))
      setError(undefined)
    } catch (e) {
      setError(e instanceof ExprError ? e.message : 'That doesn’t work out')
    }
  }

  function press(key: string) {
    setError(undefined)
    if (key === 'C') return setExpr('')
    if (key === '=') return equals()
    const text = INSERT[key] ?? (key === '÷' ? '/' : key === '×' ? '*' : key === '−' ? '-' : key)
    setExpr((x) => x + text)
    input.current?.focus()
  }

  const current = preview ?? (expr.trim() ? null : ans)
  function memoryKey(k: 'MC' | 'MR' | 'M+' | 'M−') {
    if (k === 'MC') setMemory(0)
    else if (k === 'MR') setExpr((x) => x + String(memory))
    else if (current !== null) setMemory((m) => m + (k === 'M+' ? current : -current))
  }

  useEffect(() => { input.current?.focus() }, [])

  return (
    <div className="flex h-full min-w-0 flex-col gap-3" data-testid="calculator">
      <div className="rounded-card bg-surface-2 p-3">
        <input ref={input} aria-label="Expression" value={expr} spellCheck={false} autoComplete="off"
          onChange={(e) => { setExpr(e.target.value); setError(undefined) }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === '=') { e.preventDefault(); equals() }
            if (e.key === 'Escape') setExpr('')
          }}
          placeholder="25,000 × 12 + 18%"
          className="w-full bg-transparent text-right font-mono text-base text-muted outline-none" />
        <div className="mt-1 flex items-center justify-end gap-2">
          {memory !== 0 && <span className="mr-auto text-xs text-subtle">M = {formatNumber(memory)}</span>}
          <output className="truncate text-right text-3xl font-semibold tracking-tight tabular" data-testid="calc-result">
            {error ? <span className="text-base text-critical">{error}</span> : current === null ? '…' : formatNumber(current)}
          </output>
          <button type="button" aria-label="Copy the result" className="rounded-control p-1 text-muted hover:text-fg"
            onClick={() => { if (current !== null) { void navigator.clipboard?.writeText(String(current)); toast('Copied') } }}><Copy className="size-4" /></button>
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <Segmented label="Mode" value={sci ? 'sci' : 'std'} onChange={(v) => setSci(v === 'sci')}
          options={[{ value: 'std', label: 'Standard' }, { value: 'sci', label: 'Scientific' }]} />
        {sci && <Segmented label="Angles" value={mode} onChange={setMode} options={[{ value: 'deg', label: 'Deg' }, { value: 'rad', label: 'Rad' }]} />}
        <button type="button" onClick={() => setExpr((x) => x.slice(0, -1))} aria-label="Delete last" className="ml-auto rounded-control p-2 text-muted hover:bg-surface-2"><Delete className="size-4" /></button>
      </div>

      <div className="grid grid-cols-4 gap-1.5">
        {(['MC', 'MR', 'M+', 'M−'] as const).map((k) => (
          <button key={k} type="button" onClick={() => memoryKey(k)} className="h-8 rounded-control text-xs text-muted hover:bg-surface-2">{k}</button>
        ))}
      </div>
      {sci && (
        <div className="grid grid-cols-5 gap-1.5">
          {SCI.map((k) => <Key key={k} label={k} onClick={() => press(k)} subtle />)}
        </div>
      )}
      <div className="grid grid-cols-4 gap-1.5">
        {STD.flat().map((k) => <Key key={k} label={k} onClick={() => press(k)} op={'÷×−+'.includes(k)} primary={k === '='} />)}
      </div>

      {history.length > 0 && (
        <section aria-label="History" className="flex min-h-0 flex-col gap-1">
          <h3 className="flex items-center gap-1.5 text-xs font-medium text-muted"><History className="size-3.5" />History</h3>
          <ul className="flex flex-col text-sm">
            {history.map((h, i) => (
              <li key={i}>
                <button type="button" onClick={() => setExpr(h.expr)} className="flex w-full justify-between gap-3 rounded-control px-2 py-1 text-left hover:bg-surface-2">
                  <span className="truncate font-mono text-xs text-muted">{h.expr}</span>
                  <span className="tabular">{formatNumber(h.value)}</span>
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  )
}

function Key({ label, onClick, op, primary, subtle }: { label: string; onClick: () => void; op?: boolean; primary?: boolean; subtle?: boolean }) {
  return (
    <button type="button" onClick={onClick} aria-label={label === '−' ? 'minus' : label}
      className={cn('h-11 rounded-control text-base font-medium transition-colors active:scale-[0.97]',
        primary ? 'bg-accent text-accent-fg hover:opacity-90' : op ? 'bg-surface-3 hover:bg-border' : subtle ? 'h-9 bg-surface-2 text-sm hover:bg-surface-3' : 'bg-surface-2 hover:bg-surface-3')}>
      {label}
    </button>
  )
}
