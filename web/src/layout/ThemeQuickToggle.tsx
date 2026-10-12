import { Monitor, Moon, Sun } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Menu, MenuContent, MenuRadioGroup, MenuRadioItem, MenuTrigger } from '@/components/ui/overlay'
import { usePreferences } from '@/state/PreferencesProvider'

export function ThemeQuickToggle() {
  const { prefs, dispatch, mode } = usePreferences()
  const Icon = prefs.theme.mode === 'system' ? Monitor : mode === 'dark' ? Moon : Sun
  return (
    <Menu>
      <MenuTrigger asChild>
        <Button variant="ghost" size="icon" aria-label="Theme"><Icon /></Button>
      </MenuTrigger>
      <MenuContent>
        <MenuRadioGroup value={prefs.theme.mode} onValueChange={(v) => dispatch({ type: 'theme', patch: { mode: v as 'light' } })}>
          <MenuRadioItem value="light">Light</MenuRadioItem>
          <MenuRadioItem value="dark">Dark</MenuRadioItem>
          <MenuRadioItem value="system">Match my device</MenuRadioItem>
        </MenuRadioGroup>
      </MenuContent>
    </Menu>
  )
}
