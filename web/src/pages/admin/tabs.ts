/** The admin console's tabs, in order; which ones show depends on the staff role. */
export const ADMIN_TABS_KEYS = ['overview', 'users', 'staff', 'settings', 'audit'] as const
export type AdminTab = (typeof ADMIN_TABS_KEYS)[number]
