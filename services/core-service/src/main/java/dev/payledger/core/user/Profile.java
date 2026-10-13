package dev.payledger.core.user;

import dev.payledger.core.admin.StaffRole;
import dev.payledger.core.space.SpaceSummary;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything the web app needs right after login.
 *
 * @param preferences UI customisation (theme, accent colour, density, dashboard layout...), stored as-is
 * @param staff       the user's admin-console role and what it allows; null for customers
 */
public record Profile(
        UUID id,
        String email,
        String displayName,
        Settings settings,
        Map<String, Object> preferences,
        List<SpaceSummary> spaces,
        Staff staff) {

    public record Staff(StaffRole role, Set<StaffRole.Permission> permissions) {
    }

    /** @param budgetStartDay day of the month a budget period starts (1-28), e.g. payday */
    public record Settings(String locale, String timeZone, String currency, int budgetStartDay) {
    }
}
