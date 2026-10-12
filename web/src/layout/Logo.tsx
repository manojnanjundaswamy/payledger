export function Logo({ withName = true }: { withName?: boolean }) {
  return (
    <span className="inline-flex items-center gap-2 font-semibold tracking-tight">
      <svg viewBox="0 0 32 32" className="size-7" aria-hidden>
        <rect width="32" height="32" rx="8" className="fill-fg" />
        <path d="M10 22V10h7a4 4 0 0 1 0 8h-7" fill="none" className="stroke-bg" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
      {withName && <span className="text-[1.0625rem]">PayLedger</span>}
    </span>
  )
}
