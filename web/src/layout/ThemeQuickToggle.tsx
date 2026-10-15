import { Monitor, Moon, Sun } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Menu, MenuContent, MenuLabel, MenuRadioGroup, MenuRadioItem, MenuSeparator, MenuTrigger } from '@/components/ui/overlay'
import { PALETTE_LOOK } from '@/components/ThemePalettePicker'
import { PALETTES, type PaletteId } from '@/state/preferences'
import { usePreferences } from '@/state/PreferencesProvider'

/** The top bar's theme menu: the palette and light, dark or the device's, without opening Settings. */
export function ThemeQuickToggle() {
  const { prefs, dispatch, mode } = usePreferences()
  const Icon = prefs.theme.mode === 'system' ? Monitor : mode === 'dark' ? Moon : Sun
  return (
    <Menu>
      <MenuTrigger asChild>
        <Button variant="ghost" size="icon" aria-label="Theme"><Icon /></Button>
      </MenuTrigger>
      <MenuContent>
        <MenuLabel>Theme palette</MenuLabel>
        <MenuRadioGroup value={prefs.theme.palette} onValueChange={(v) => dispatch({ type: 'theme', patch: { palette: v as PaletteId, accent: null } })}>
          {PALETTES.map((p) => (
            <MenuRadioItem key={p.id} value={p.id}>
              <span className="flex items-center gap-2">
                <span aria-hidden className="size-3.5 rounded-full border border-black/10" style={{ background: PALETTE_LOOK[p.id][mode].accent }} />
                {p.name}
              </span>
            </MenuRadioItem>
          ))}
        </MenuRadioGroup>
        <MenuSeparator />
        <MenuRadioGroup value={prefs.theme.mode} onValueChange={(v) => dispatch({ type: 'theme', patch: { mode: v as 'light' } })}>
          <MenuRadioItem value="light">Light</MenuRadioItem>
          <MenuRadioItem value="dark">Dark</MenuRadioItem>
          <MenuRadioItem value="system">Match my device</MenuRadioItem>
        </MenuRadioGroup>
      </MenuContent>
    </Menu>
  )
}
