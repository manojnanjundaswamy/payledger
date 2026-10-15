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

    /**
     * @param budgetStartDay day of the month a budget period starts (1-28), e.g. payday
     * @param upiId          where friends can pay you (shown only in groups you share)
     * @param country        ISO 3166 code, or "ZZ" for somewhere not listed; null until the region is set up
     * @param fyStartMonth   the month the financial year starts (India 4: April–March; 1: calendar year)
     */
    public record Settings(String locale, String timeZone, String currency, int budgetStartDay, String upiId, String country, int fyStartMonth) {
    }
}
