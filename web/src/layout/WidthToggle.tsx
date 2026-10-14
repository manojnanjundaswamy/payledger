import { Maximize2, Minimize2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Tooltip } from '@/components/ui/controls'
import { usePreferences } from '@/state/PreferencesProvider'

/** One click between a readable column and the whole window (also in Settings → Appearance). */
export function WidthToggle() {
  const { prefs, dispatch } = usePreferences()
  const wide = prefs.theme.width === 'wide'
  const label = wide ? 'Narrower pages' : 'Wider pages'
  return (
    <Tooltip content={label}>
      <Button variant="ghost" size="icon" aria-label={label} aria-pressed={wide} className="hidden lg:inline-flex"
        onClick={() => dispatch({ type: 'theme', patch: { width: wide ? 'comfortable' : 'wide' } })}>
        {wide ? <Minimize2 /> : <Maximize2 />}
      </Button>
    </Tooltip>
  )
}
