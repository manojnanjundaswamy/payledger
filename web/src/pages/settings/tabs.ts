/** Kept apart from the settings page so the router can validate ?tab= without loading the page. */
export const SETTINGS_TAB_KEYS = ['appearance', 'profile', 'regional', 'dashboards', 'plan', 'account'] as const
export type SettingsTab = (typeof SETTINGS_TAB_KEYS)[number]
