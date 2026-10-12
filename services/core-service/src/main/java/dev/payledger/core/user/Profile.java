package dev.payledger.core.user;

import dev.payledger.core.space.SpaceSummary;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything the web app needs right after login.
 *
 * @param preferences UI customisation (theme, accent colour, density, dashboard layout...), stored as-is
 */
public record Profile(
        UUID id,
        String email,
        String displayName,
        Settings settings,
        Map<String, Object> preferences,
        List<SpaceSummary> spaces) {

    /** @param budgetStartDay day of the month a budget period starts (1-28), e.g. payday */
    public record Settings(String locale, String timeZone, String currency, int budgetStartDay) {
    }
}
