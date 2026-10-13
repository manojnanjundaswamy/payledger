import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ArrowDownUp, Copy } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Segmented } from '@/components/ui/controls'
import { api } from '@/lib/api'
import { Select } from '@/pages/money/parts'
import { amountInWords } from '../finance'
import { Line, Note, NumberField, Result, num, rupees } from './parts'

interface Fx { base: string; date: string; rates: Record<string, number>; source: string }

/** The currencies Indians deal with most come first. */
const FIRST = ['USD', 'AED', 'EUR', 'GBP', 'SGD', 'SAR', 'CAD', 'AUD', 'JPY', 'QAR', 'KWD', 'OMR', 'BHD']

/** Rupees to other currencies and back, at the reference rates of the last working day. */
export function CurrencyConverter() {
  const { data: fx, isError } = useQuery({ queryKey: ['fx'], queryFn: () => api<Fx>('/api/core/fx'), staleTime: 60 * 60_000, retry: 1 })
  const [amount, setAmount] = useState('1,000')
  const [other, setOther] = useState('USD')
  const [toInr, setToInr] = useState(false)
  const [manual, setManual] = useState('')
  const a = num(amount) ?? 0
  // Rates are "1 INR = x OTHER"; a typed rate is "1 OTHER = y INR" (how people quote it).
  const perInr = manual && num(manual) ? 1 / num(manual)! : fx?.rates[other]
  const result = perInr ? (toInr ? a / perInr : a * perInr) : null
  const codes = fx ? [...FIRST.filter((c) => c in fx.rates), ...Object.keys(fx.rates).filter((c) => !FIRST.includes(c)).sort()] : FIRST
  const fmt = (v: number, code: string) => v.toLocaleString('en-IN', { style: 'currency', currency: code, maximumFractionDigits: 2 })

  return (
    <div className="flex flex-col gap-4">
      <div className="grid gap-3 sm:grid-cols-[1fr_auto_1fr] sm:items-end">
        <NumberField id="fx-amount" label={toInr ? `Amount in ${other}` : 'Amount in ₹'} value={amount} onChange={setAmount} />
        <Button variant="ghost" size="icon" aria-label="Swap" onClick={() => setToInr(!toInr)} className="self-end"><ArrowDownUp /></Button>
        <label className="flex flex-col gap-1 text-sm">
          <span className="font-medium">Other currency</span>
          <Select aria-label="Other currency" value={other} onChange={(e) => setOther(e.target.value)}>
            {codes.map((c) => <option key={c} value={c}>{c}</option>)}
          </Select>
        </label>
      </div>
      {result !== null && (
        <Result label={toInr ? 'In rupees' : `In ${other}`} value={toInr ? rupees(result, 2) : fmt(result, other)} testId="fx-result">
          {perInr && <Line label={`1 ${other}`} value={rupees(1 / perInr, 2)} />}
        </Result>
      )}
      <NumberField id="fx-manual" label={`Your rate (optional): 1 ${other} in ₹`} value={manual} onChange={setManual}
        hint="Use the rate your bank or card actually gives you; it usually differs from the reference rate by 1–3%." />
      {isError && !manual && <Note>Live rates are unavailable right now; type a rate above to convert.</Note>}
      {fx && <Note>Reference rates of {fx.date} ({fx.source}). Gulf currencies follow their fixed rate to the US dollar.</Note>}
    </div>
  )
}

/** For cheques and forms: the amount in Indian words. */
export function AmountInWords() {
  const [amount, setAmount] = useState('1,50,250.50')
  const a = num(amount)
  const words = a !== null && a >= 0 ? amountInWords(a) : ''
  return (
    <div className="flex flex-col gap-4">
      <NumberField id="words-amount" label="Amount" value={amount} onChange={setAmount} suffix="₹" />
      {words && (
        <div className="flex flex-col gap-2 rounded-card bg-surface-2 p-4">
          <p className="text-base leading-relaxed" data-testid="words">{words}</p>
          <Button variant="outline" size="sm" className="self-start" onClick={() => { void navigator.clipboard?.writeText(words); toast('Copied') }}><Copy />Copy</Button>
        </div>
      )}
    </div>
  )
}

/** A bill shared at the table: tip, people, who pays what (rounded up to the rupee). */
export function BillSplitter() {
  const [bill, setBill] = useState('2,400')
  const [people, setPeople] = useState('4')
  const [tip, setTip] = useState<'0' | '5' | '10' | '15'>('0')
  const b = num(bill) ?? 0
  const n = Math.max(1, Math.round(num(people) ?? 1))
  const total = b * (1 + Number(tip) / 100)
  return (
    <div className="flex flex-col gap-4">
      <div className="grid gap-3 sm:grid-cols-2">
        <NumberField id="split-bill" label="Bill" value={bill} onChange={setBill} suffix="₹" />
        <NumberField id="split-people" label="People" value={people} onChange={setPeople} />
      </div>
      <Segmented label="Tip" value={tip} onChange={setTip} options={[{ value: '0', label: 'No tip' }, { value: '5', label: '5%' }, { value: '10', label: '10%' }, { value: '15', label: '15%' }]} />
      {b > 0 && (
        <Result label="Each pays" value={rupees(Math.ceil(total / n))} testId="split-each">
          <Line label="Total with tip" value={rupees(total)} />
        </Result>
      )}
      <Note>For splits you want to keep track of, use a group: it remembers who paid and settles up in the fewest payments.</Note>
    </div>
  )
}

/** Percent of, change between two numbers, and a discount. */
export function PercentCalculator() {
  const [kind, setKind] = useState<'of' | 'change' | 'discount'>('of')
  const [a, setA] = useState('18')
  const [b, setB] = useState('5,000')
  const x = num(a) ?? 0
  const y = num(b) ?? 0
  const value = kind === 'of' ? (x / 100) * y : kind === 'change' ? (x !== 0 ? ((y - x) / x) * 100 : 0) : y * (1 - x / 100)
  return (
    <div className="flex flex-col gap-4">
      <Segmented label="Calculate" value={kind} onChange={setKind}
        options={[{ value: 'of', label: 'X% of Y' }, { value: 'change', label: 'Change from X to Y' }, { value: 'discount', label: 'Y after X% off' }]} />
      <div className="grid gap-3 sm:grid-cols-2">
        <NumberField id="pct-a" label={kind === 'change' ? 'From' : 'Percent'} value={a} onChange={setA} suffix={kind === 'change' ? '' : '%'} />
        <NumberField id="pct-b" label={kind === 'change' ? 'To' : 'Amount'} value={b} onChange={setB} />
      </div>
      <Result label="Answer" value={kind === 'change' ? `${value >= 0 ? '+' : ''}${value.toFixed(2)}%` : value.toLocaleString('en-IN', { maximumFractionDigits: 2 })} testId="pct-result" />
    </div>
  )
}
