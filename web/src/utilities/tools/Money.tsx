import { useState } from 'react'
import { Segmented } from '@/components/ui/controls'
import { amortize, futureCost, lumpsum, recurringDeposit, sip } from '../finance'
import { Line, Note, NumberField, Result, SplitBar, num, rupees } from './parts'

/** Loan EMI with the year-by-year split, and what an extra payment every month saves. */
export function LoanCalculator() {
  const [amount, setAmount] = useState('25L')
  const [rate, setRate] = useState('8.5')
  const [years, setYears] = useState('20')
  const [extra, setExtra] = useState('')
  const p = num(amount) ?? 0
  const r = num(rate) ?? 0
  const months = Math.round((num(years) ?? 0) * 12)
  const plan = p > 0 && months > 0 ? amortize(p, r, months) : null
  const faster = plan && (num(extra) ?? 0) > 0 ? amortize(p, r, months, num(extra)!) : null

  return (
    <div className="flex flex-col gap-4">
      <div className="grid gap-3 sm:grid-cols-3">
        <NumberField id="loan-amount" label="Loan amount" value={amount} onChange={setAmount} suffix="₹" hint="25L, 5,00,000…" />
        <NumberField id="loan-rate" label="Interest" value={rate} onChange={setRate} suffix="% a year" />
        <NumberField id="loan-years" label="Tenure" value={years} onChange={setYears} suffix="years" />
      </div>
      {plan && (
        <>
          <Result label="Monthly EMI" value={rupees(plan.emi)} testId="emi">
            <Line label="Total interest" value={rupees(plan.totalInterest)} />
            <Line label="Total you pay" value={rupees(plan.totalPaid)} strong />
          </Result>
          <SplitBar a={p} b={plan.totalInterest} aLabel="Principal" bLabel="Interest" />
          <NumberField id="loan-extra" label="Pay extra every month (optional)" value={extra} onChange={setExtra} suffix="₹" />
          {faster && (
            <Result label="With the extra payment" value={`${Math.floor(faster.months / 12)} y ${faster.months % 12} m`} testId="loan-faster">
              <Line label="Done sooner by" value={`${Math.floor((plan.months - faster.months) / 12)} y ${(plan.months - faster.months) % 12} m`} />
              <Line label="Interest saved" value={rupees(plan.totalInterest - faster.totalInterest)} strong />
            </Result>
          )}
          <details className="text-sm">
            <summary className="cursor-pointer text-muted">Year by year</summary>
            <table className="mt-2 w-full text-xs tabular">
              <thead><tr className="text-subtle"><th className="text-left font-medium">Year</th><th className="text-right font-medium">Principal</th><th className="text-right font-medium">Interest</th><th className="text-right font-medium">Left to pay</th></tr></thead>
              <tbody>
                {plan.years.map((y) => (
                  <tr key={y.year} className="border-t border-border"><td className="py-1">{y.year}</td><td className="text-right">{rupees(y.principal)}</td><td className="text-right">{rupees(y.interest)}</td><td className="text-right">{rupees(y.balance)}</td></tr>
                ))}
              </tbody>
            </table>
          </details>
        </>
      )}
    </div>
  )
}

/** SIP with an optional yearly step-up. */
export function SipCalculator() {
  const [monthly, setMonthly] = useState('10,000')
  const [ret, setRet] = useState('12')
  const [years, setYears] = useState('15')
  const [step, setStep] = useState('0')
  const m = num(monthly) ?? 0
  const result = m > 0 ? sip(m, num(ret) ?? 0, num(years) ?? 0, num(step) ?? 0) : null
  return (
    <div className="flex flex-col gap-4">
      <div className="grid gap-3 sm:grid-cols-2">
        <NumberField id="sip-monthly" label="Every month" value={monthly} onChange={setMonthly} suffix="₹" />
        <NumberField id="sip-return" label="Expected return" value={ret} onChange={setRet} suffix="% a year" />
        <NumberField id="sip-years" label="For" value={years} onChange={setYears} suffix="years" />
        <NumberField id="sip-step" label="Raise it every year by" value={step} onChange={setStep} suffix="%" hint="A step-up SIP grows with your salary." />
      </div>
      {result && (
        <>
          <Result label="You'd have" value={rupees(result.value)} testId="sip-value">
            <Line label="You put in" value={rupees(result.invested)} />
            <Line label="Growth" value={rupees(result.gains)} strong />
          </Result>
          <SplitBar a={result.invested} b={result.gains} aLabel="Invested" bLabel="Growth" />
          <Note>Returns aren't guaranteed; equity funds have averaged 10–12% a year over long periods, with bad years along the way.</Note>
        </>
      )}
    </div>
  )
}

/** A fixed deposit or any one-time investment. */
export function FdCalculator() {
  const [amount, setAmount] = useState('1L')
  const [rate, setRate] = useState('7')
  const [years, setYears] = useState('5')
  const [compound, setCompound] = useState<'4' | '12' | '1'>('4')
  const p = num(amount) ?? 0
  const result = p > 0 ? lumpsum(p, num(rate) ?? 0, num(years) ?? 0, Number(compound)) : null
  return (
    <div className="flex flex-col gap-4">
      <div className="grid gap-3 sm:grid-cols-3">
        <NumberField id="fd-amount" label="Deposit" value={amount} onChange={setAmount} suffix="₹" />
        <NumberField id="fd-rate" label="Interest" value={rate} onChange={setRate} suffix="% a year" />
        <NumberField id="fd-years" label="For" value={years} onChange={setYears} suffix="years" />
      </div>
      <Segmented label="Interest added" value={compound} onChange={setCompound}
        options={[{ value: '4', label: 'Quarterly (FDs)' }, { value: '12', label: 'Monthly' }, { value: '1', label: 'Yearly' }]} />
      {result && (
        <Result label="At maturity" value={rupees(result.value)} testId="fd-value">
          <Line label="Interest earned" value={rupees(result.gains)} strong />
        </Result>
      )}
      <Note>FD interest is taxed at your slab rate; banks deduct TDS above ₹40,000 a year (₹50,000 for seniors).</Note>
    </div>
  )
}

export function RdCalculator() {
  const [monthly, setMonthly] = useState('5,000')
  const [rate, setRate] = useState('7')
  const [months, setMonths] = useState('24')
  const m = num(monthly) ?? 0
  const result = m > 0 ? recurringDeposit(m, num(rate) ?? 0, Math.round(num(months) ?? 0)) : null
  return (
    <div className="flex flex-col gap-4">
      <div className="grid gap-3 sm:grid-cols-3">
        <NumberField id="rd-monthly" label="Every month" value={monthly} onChange={setMonthly} suffix="₹" />
        <NumberField id="rd-rate" label="Interest" value={rate} onChange={setRate} suffix="% a year" />
        <NumberField id="rd-months" label="For" value={months} onChange={setMonths} suffix="months" />
      </div>
      {result && (
        <Result label="At maturity" value={rupees(result.value)} testId="rd-value">
          <Line label="You put in" value={rupees(result.invested)} />
          <Line label="Interest" value={rupees(result.gains)} strong />
        </Result>
      )}
    </div>
  )
}

/** What something will cost later: school fees, a car, retirement spending. */
export function InflationCalculator() {
  const [today, setToday] = useState('1L')
  const [rate, setRate] = useState('6')
  const [years, setYears] = useState('10')
  const t = num(today) ?? 0
  const later = futureCost(t, num(rate) ?? 0, num(years) ?? 0)
  return (
    <div className="flex flex-col gap-4">
      <div className="grid gap-3 sm:grid-cols-3">
        <NumberField id="inf-today" label="Costs today" value={today} onChange={setToday} suffix="₹" />
        <NumberField id="inf-rate" label="Inflation" value={rate} onChange={setRate} suffix="% a year" hint="India: about 5–6%; education 8–10%." />
        <NumberField id="inf-years" label="In" value={years} onChange={setYears} suffix="years" />
      </div>
      {t > 0 && (
        <Result label="It will cost" value={rupees(later)} testId="inflation-value">
          <Line label="Today's money is worth" value={`${Math.round((t / later) * 100)}% of that`} />
        </Result>
      )}
    </div>
  )
}
