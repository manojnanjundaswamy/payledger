import { useState } from 'react'
import { Check } from 'lucide-react'
import { Segmented, Switch } from '@/components/ui/controls'
import { cn } from '@/lib/cn'
import { GST_RATES, TAX_YEAR, gst, incomeTax, type AgeBand, type Deductions, type TaxResult } from '../finance'
import { Line, Note, NumberField, Result, num, rupees, UseIt } from './parts'

/** Old regime against new, side by side, with the cheaper one marked. */
export function TaxCalculator() {
  const [income, setIncome] = useState('15L')
  const [salaried, setSalaried] = useState(true)
  const [age, setAge] = useState<AgeBand>('below60')
  const [d, setD] = useState<Record<keyof Deductions, string>>({ sec80C: '1.5L', sec80D: '25k', nps80CCD1B: '', homeLoanInterest: '', hra: '', other: '' })
  const gross = num(income) ?? 0
  const deductions: Deductions = {
    sec80C: num(d.sec80C) ?? 0, sec80D: num(d.sec80D) ?? 0, nps80CCD1B: num(d.nps80CCD1B) ?? 0,
    homeLoanInterest: num(d.homeLoanInterest) ?? 0, hra: num(d.hra) ?? 0, other: num(d.other) ?? 0,
  }
  const both = gross > 0 ? [incomeTax(gross, 'new', { salaried, age }), incomeTax(gross, 'old', { salaried, age, deductions })] : null
  const better = both ? (both[0]!.total <= both[1]!.total ? 'new' : 'old') : null
  const set = (k: keyof Deductions) => (v: string) => setD({ ...d, [k]: v })

  return (
    <div className="flex flex-col gap-4">
      <div className="grid gap-3 sm:grid-cols-2">
        <NumberField id="tax-income" label="Yearly income" value={income} onChange={setIncome} suffix="₹" hint="Salary and other income, before tax." />
        <div className="flex flex-col gap-2 text-sm">
          <span className="font-medium">Age</span>
          <Segmented label="Age" value={age} onChange={setAge} options={[{ value: 'below60', label: 'Under 60' }, { value: '60to80', label: '60–80' }, { value: 'above80', label: '80+' }]} />
          <label className="flex items-center gap-2 text-muted"><Switch label="Salaried" checked={salaried} onCheckedChange={setSalaried} />Salaried or pensioner (standard deduction)</label>
        </div>
      </div>
      <details className="rounded-card border border-border p-3 text-sm">
        <summary className="cursor-pointer font-medium">Deductions (old regime only)</summary>
        <div className="mt-3 grid gap-3 sm:grid-cols-3">
          <NumberField id="tax-80c" label="80C (PF, ELSS, PPF…)" value={d.sec80C} onChange={set('sec80C')} hint="Up to ₹1.5L" />
          <NumberField id="tax-80d" label="80D health insurance" value={d.sec80D} onChange={set('sec80D')} />
          <NumberField id="tax-nps" label="NPS 80CCD(1B)" value={d.nps80CCD1B} onChange={set('nps80CCD1B')} hint="Up to ₹50,000" />
          <NumberField id="tax-home" label="Home loan interest" value={d.homeLoanInterest} onChange={set('homeLoanInterest')} hint="Up to ₹2L (self-occupied)" />
          <NumberField id="tax-hra" label="HRA exemption" value={d.hra} onChange={set('hra')} />
          <NumberField id="tax-other" label="Other deductions" value={d.other} onChange={set('other')} />
        </div>
      </details>
      {both && (
        <div className="grid gap-3 sm:grid-cols-2">
          {both.map((t) => <RegimeCard key={t.regime} t={t} best={better === t.regime} />)}
          <UseIt amount={both.find((t) => t.regime === better)!.total} description={`Income tax ${TAX_YEAR}`} category="Taxes" />
        </div>
      )}
      {both && better && (
        <p className="text-sm" data-testid="tax-better">
          The <b>{better} regime</b> saves you <b>{rupees(Math.abs(both[0]!.total - both[1]!.total))}</b> a year.
        </p>
      )}
      <Note>{TAX_YEAR} rules (unchanged by Budget 2026). An estimate for planning, not tax advice: capital gains, special rates and some reliefs aren't covered.</Note>
    </div>
  )
}

function RegimeCard({ t, best }: { t: TaxResult; best: boolean }) {
  return (
    <div className={cn('flex flex-col gap-2 rounded-card border p-4', best ? 'border-accent bg-accent-soft' : 'border-border bg-surface-2')} data-testid={`tax-${t.regime}`}>
      <span className="flex items-center justify-between text-xs text-muted">
        {t.regime === 'new' ? 'New regime' : 'Old regime'}
        {best && <span className="flex items-center gap-1 font-medium text-fg"><Check className="size-3.5" />Lower</span>}
      </span>
      <span className="text-2xl font-semibold tracking-tight tabular">{rupees(t.total)}</span>
      <dl className="flex flex-col gap-1 text-sm">
        <Line label="Taxable income" value={rupees(t.taxable)} />
        {t.rebate > 0 && <Line label="Rebate (87A)" value={`−${rupees(t.rebate)}`} />}
        {t.surcharge > 0 && <Line label="Surcharge" value={rupees(t.surcharge)} />}
        <Line label="Cess (4%)" value={rupees(t.cess)} />
        <Line label="Effective rate" value={`${t.effectiveRatePct.toFixed(1)}%`} />
        <Line label="A month" value={rupees(t.total / 12)} strong />
      </dl>
    </div>
  )
}

export function GstCalculator() {
  const [amount, setAmount] = useState('1,000')
  const [rate, setRate] = useState<string>('18')
  const [inclusive, setInclusive] = useState(false)
  const [interstate, setInterstate] = useState(false)
  const a = num(amount) ?? 0
  const r = gst(a, num(rate) ?? 0, inclusive)
  return (
    <div className="flex flex-col gap-4">
      <NumberField id="gst-amount" label={inclusive ? 'Price including GST' : 'Price before GST'} value={amount} onChange={setAmount} suffix="₹" />
      <div className="flex flex-wrap items-center gap-3">
        <Segmented label="GST rate" value={rate} onChange={setRate} options={GST_RATES.map((g) => ({ value: String(g), label: `${g}%` }))} />
        <label className="flex items-center gap-2 text-sm text-muted"><Switch label="Price includes GST" checked={inclusive} onCheckedChange={setInclusive} />Price includes GST</label>
        <label className="flex items-center gap-2 text-sm text-muted"><Switch label="Between states" checked={interstate} onCheckedChange={setInterstate} />Between states (IGST)</label>
      </div>
      {a > 0 && (
        <Result label={inclusive ? 'Price before GST' : 'Price with GST'} value={rupees(inclusive ? r.base : r.total, 2)} testId="gst-result">
          <Line label="GST" value={rupees(r.tax, 2)} />
          {interstate ? <Line label={`IGST ${rate}%`} value={rupees(r.tax, 2)} />
            : <><Line label={`CGST ${Number(rate) / 2}%`} value={rupees(r.cgst, 2)} /><Line label={`SGST ${Number(rate) / 2}%`} value={rupees(r.sgst, 2)} /></>}
        </Result>
      )}
      {a > 0 && (
        <UseIt amount={inclusive ? a : r.total} description={`Purchase incl. ${rate}% GST`} />
      )}
      <Note>GST 2.0 (from 22 Sept 2025): 5% and 18% for most goods and services, 40% for a short list of luxury and sin goods.</Note>
    </div>
  )
}
