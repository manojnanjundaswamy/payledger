import { Banknote, Calculator as CalcIcon, Scale, Coins, FileText, Landmark, Percent, PiggyBank, Receipt, ReceiptIndianRupee, Repeat, TrendingUp, Users, type LucideIcon } from 'lucide-react'
import { Calculator } from './tools/Calculator'
import { AmountInWords, BillSplitter, CurrencyConverter, PercentCalculator } from './tools/Everyday'
import { FdCalculator, InflationCalculator, LoanCalculator, RdCalculator, SipCalculator } from './tools/Money'
import { GstCalculator, TaxCalculator } from './tools/Tax'
import { AffordTool } from './tools/Afford'

export type ToolGroup = 'Calculate' | 'Loans & savings' | 'Tax' | 'Everyday'

export interface Tool {
  name: string
  /** One line for the Utilities page. */
  does: string
  icon: LucideIcon
  group: ToolGroup
  component: () => React.JSX.Element
  /** Default floating window size. */
  size: { w: number; h: number }
}

export const TOOLS = {
  calculator: { name: 'Calculator', does: 'Standard and scientific, with lakh and crore, memory and history.', icon: CalcIcon, group: 'Calculate', component: Calculator, size: { w: 360, h: 600 } },
  percent: { name: 'Percentages', does: 'X% of Y, change between two numbers, a discount.', icon: Percent, group: 'Calculate', component: PercentCalculator, size: { w: 420, h: 420 } },
  loan: { name: 'Loan EMI', does: 'EMI, total interest, year by year, and what prepaying saves.', icon: Landmark, group: 'Loans & savings', component: LoanCalculator, size: { w: 520, h: 620 } },
  sip: { name: 'SIP', does: 'What a monthly SIP grows to, with a yearly step-up.', icon: TrendingUp, group: 'Loans & savings', component: SipCalculator, size: { w: 480, h: 560 } },
  fd: { name: 'Fixed deposit', does: 'Maturity value of an FD or any one-time investment.', icon: PiggyBank, group: 'Loans & savings', component: FdCalculator, size: { w: 480, h: 460 } },
  rd: { name: 'Recurring deposit', does: 'What a monthly RD pays at maturity.', icon: Repeat, group: 'Loans & savings', component: RdCalculator, size: { w: 480, h: 420 } },
  inflation: { name: 'Future cost', does: 'What something will cost in a few years, with inflation.', icon: Coins, group: 'Loans & savings', component: InflationCalculator, size: { w: 480, h: 420 } },
  tax: { name: 'Income tax (India)', does: 'Old regime against new for this year, with deductions.', icon: ReceiptIndianRupee, group: 'Tax', component: TaxCalculator, size: { w: 600, h: 680 } },
  gst: { name: 'GST', does: 'Add or take out GST; CGST, SGST or IGST.', icon: Receipt, group: 'Tax', component: GstCalculator, size: { w: 480, h: 480 } },
  currency: { name: 'Currency', does: 'Rupees to dollars, dirhams, euros and back.', icon: Banknote, group: 'Everyday', component: CurrencyConverter, size: { w: 480, h: 520 } },
  split: { name: 'Split a bill', does: 'Each person’s share, with a tip.', icon: Users, group: 'Everyday', component: BillSplitter, size: { w: 420, h: 440 } },
  afford: { name: 'Can I afford it?', does: 'A purchase or a new EMI, checked against your plan and savings.', icon: Scale, group: 'Everyday', component: AffordTool, size: { w: 460, h: 440 } },
  words: { name: 'Amount in words', does: 'For cheques and forms, in lakh and crore (India).', icon: FileText, group: 'Everyday', component: AmountInWords, size: { w: 440, h: 340 } },
} satisfies Record<string, Tool>

export type ToolId = keyof typeof TOOLS
export const TOOL_IDS = Object.keys(TOOLS) as ToolId[]
export const GROUPS: ToolGroup[] = ['Calculate', 'Loans & savings', 'Tax', 'Everyday']
