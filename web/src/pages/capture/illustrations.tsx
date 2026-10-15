/**
 * Small drawings for the auto-capture guide. Drawn in SVG and HTML with the theme's colours (no image files), so
 * they look right in light and dark mode and stay crisp on any screen.
 */
import { ArrowRight, Check, FileSpreadsheet, Mail, MessageSquareText, Settings, Share2 } from 'lucide-react'
import { cn } from '@/lib/cn'

/** A bank's email travelling to PayLedger and becoming an entry. */
export function ForwardFlow({ className }: { className?: string }) {
  return (
    <div className={cn('flex items-center justify-center gap-2 sm:gap-3', className)} aria-hidden>
      <Bubble><Mail className="size-5" /><span>Bank alert</span></Bubble>
      <ArrowRight className="size-4 shrink-0 text-subtle" />
      <Bubble><span className="font-semibold text-[#ea4335]">M</span><span>Gmail filter</span></Bubble>
      <ArrowRight className="size-4 shrink-0 text-subtle" />
      <Bubble accent><Check className="size-5" /><span>To review</span></Bubble>
    </div>
  )
}

function Bubble({ children, accent }: { children: React.ReactNode; accent?: boolean }) {
  return (
    <span className={cn('flex min-w-0 flex-col items-center gap-1 rounded-card border px-2 py-2 text-[0.6875rem] font-medium sm:px-3',
      accent ? 'border-transparent bg-accent text-accent-fg' : 'border-border bg-surface text-muted')}>
      {children}
    </span>
  )
}

/** A phone with two bank SMS, one picked out, and the share arrow. */
export function PhoneSms({ share, className }: { share?: boolean; className?: string }) {
  return (
    <svg viewBox="0 0 160 200" className={cn('h-40 w-auto', className)} role="img" aria-label="A phone showing bank SMS">
      <rect x="30" y="6" width="100" height="188" rx="16" fill="var(--surface)" stroke="var(--border-strong)" strokeWidth="2" />
      <rect x="64" y="12" width="32" height="5" rx="2.5" fill="var(--border-strong)" />
      <text x="42" y="36" fontSize="8" fill="var(--fg-muted)" fontFamily="inherit">HDFC Bank</text>
      <rect x="40" y="44" width="80" height="34" rx="8" fill="var(--surface-2)" />
      <rect x="46" y="51" width="62" height="4" rx="2" fill="var(--fg-muted)" opacity="0.6" />
      <rect x="46" y="59" width="54" height="4" rx="2" fill="var(--fg-muted)" opacity="0.6" />
      <rect x="46" y="67" width="40" height="4" rx="2" fill="var(--fg-muted)" opacity="0.6" />
      <rect x="40" y="86" width="80" height="34" rx="8" fill="var(--accent-soft)" stroke="var(--accent)" strokeWidth="1.5" />
      <rect x="46" y="93" width="62" height="4" rx="2" fill="var(--accent)" opacity="0.8" />
      <rect x="46" y="101" width="50" height="4" rx="2" fill="var(--accent)" opacity="0.8" />
      <rect x="46" y="109" width="36" height="4" rx="2" fill="var(--accent)" opacity="0.8" />
      {share ? (
        <g>
          <rect x="44" y="132" width="72" height="44" rx="8" fill="var(--surface)" stroke="var(--border)" />
          <text x="52" y="147" fontSize="8" fill="var(--fg)">Share to</text>
          <circle cx="60" cy="162" r="7" fill="var(--accent)" />
          <text x="57" y="165" fontSize="8" fill="var(--accent-fg)" fontWeight="700">P</text>
          <text x="71" y="165" fontSize="8" fill="var(--fg)">PayLedger</text>
        </g>
      ) : (
        <g>
          <rect x="62" y="132" width="36" height="16" rx="8" fill="var(--fg)" />
          <text x="70" y="143" fontSize="8" fill="var(--bg)">Copy</text>
        </g>
      )}
    </svg>
  )
}

/** Gmail's settings page, reduced to what to click. */
export function GmailSettings({ step }: { step: 'forwarding' | 'filter' }) {
  return (
    <div className="overflow-hidden rounded-card border border-border bg-surface text-[0.6875rem] shadow-card" aria-hidden>
      <div className="flex items-center gap-2 border-b border-border bg-surface-2 px-3 py-1.5">
        <span className="font-semibold text-[#ea4335]">M</span><span className="text-muted">Gmail</span>
        <Settings className="ml-auto size-3.5 text-muted" />
      </div>
      {step === 'forwarding' ? (
        <div className="flex flex-col gap-2 p-3">
          <div className="flex flex-wrap gap-x-3 gap-y-1 border-b border-border pb-1.5 text-muted">
            <span>General</span><span>Labels</span><span>Filters</span>
            <span className="border-b-2 border-accent pb-1 font-semibold text-fg">Forwarding and POP/IMAP</span>
          </div>
          <span className="self-start rounded-control border border-accent bg-accent-soft px-2 py-1 font-medium text-fg">Add a forwarding address</span>
          <span className="text-subtle">Don't turn on "Forward a copy of incoming mail": the filter does it, only for bank alerts.</span>
        </div>
      ) : (
        <div className="flex flex-col gap-1.5 p-3">
          <span className="text-muted">Has the words / From</span>
          <span className="truncate rounded-control border border-border bg-surface-2 px-2 py-1 font-mono text-[0.625rem]">hdfcbank.net OR hdfcbank.bank.in OR icicibank.com …</span>
          <span className="flex items-center gap-1.5"><span className="flex size-3.5 items-center justify-center rounded-[3px] bg-accent text-accent-fg"><Check className="size-2.5" /></span>
            <span className="font-medium text-fg">Forward it to:</span><span className="truncate text-muted">your PayLedger address</span></span>
          <span className="self-end rounded-control bg-[#1a73e8] px-2 py-1 font-medium text-white">Create filter</span>
        </div>
      )}
    </div>
  )
}

export function MethodIcon({ method }: { method: 'email' | 'paste' | 'share' | 'statement' }) {
  const Icon = method === 'email' ? Mail : method === 'paste' ? MessageSquareText : method === 'statement' ? FileSpreadsheet : Share2
  return <span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-accent-soft text-accent"><Icon className="size-5" /></span>
}
