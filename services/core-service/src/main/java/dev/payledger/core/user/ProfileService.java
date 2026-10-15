package dev.payledger.core.user;

import dev.payledger.core.admin.StaffService;
import dev.payledger.core.space.SpaceQueries;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Service
public class ProfileService {

    /** UI settings only; a dashboard layout fits comfortably. Stops the column being used as free storage. */
    static final int MAX_PREFERENCES_BYTES = 16_384;

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final SpaceQueries spaces;
    private final StaffService staff;

    public ProfileService(JdbcClient jdbc, JsonMapper json, SpaceQueries spaces, StaffService staff) {
        this.jdbc = jdbc;
        this.json = json;
        this.spaces = spaces;
        this.staff = staff;
    }

    @Transactional(readOnly = true)
    public Profile get(CurrentUser user) {
        Row row = jdbc.sql("""
                        select display_name, locale, time_zone, currency, budget_start_day, preferences::text as preferences, upi_id,
                               country, fy_start_month
                        from users where id = :id
                        """)
                .param("id", user.id())
                .query(Row.class)
                .single();
        return new Profile(user.id(), user.email(), row.displayName(),
                new Profile.Settings(row.locale(), row.timeZone(), row.currency(), row.budgetStartDay(), row.upiId(), row.country(), row.fyStartMonth()),
                json.readValue(row.preferences(), JSON_OBJECT),
                spaces.spacesOf(user.id()),
                staff.roleOf(user).map(role -> new Profile.Staff(role, role.permissions())).orElse(null));
    }

    /** Null fields are left unchanged. {@code preferences} replaces the stored object as a whole. */
    @Transactional
    public Profile update(CurrentUser user, ProfileUpdate update) {
        String preferences = update.preferences() == null ? null : json.writeValueAsString(update.preferences());
        if (preferences != null && preferences.getBytes(StandardCharsets.UTF_8).length > MAX_PREFERENCES_BYTES) {
            throw new PreferencesTooLargeException(MAX_PREFERENCES_BYTES);
        }
        jdbc.sql("""
                        update users set
                            display_name     = coalesce(:displayName, display_name),
                            locale           = coalesce(:locale, locale),
                            time_zone        = coalesce(:timeZone, time_zone),
                            currency         = coalesce(:currency, currency),
                            budget_start_day = coalesce(:budgetStartDay, budget_start_day),
                            preferences      = coalesce(cast(:preferences as jsonb), preferences),
                            upi_id           = case when cast(:upiId as text) is null then upi_id when :upiId = '' then null else :upiId end,
                            country          = coalesce(:country, country),
                            fy_start_month   = coalesce(:fyStartMonth, fy_start_month),
                            updated_at       = now()
                        where id = :id
                        """)
                .param("displayName", update.displayName() == null ? null : update.displayName().strip())
                .param("locale", update.locale())
                .param("timeZone", update.timeZone())
                .param("currency", update.currency())
                .param("budgetStartDay", update.budgetStartDay())
                .param("preferences", preferences)
                .param("upiId", update.upiId() == null ? null : update.upiId().strip())
                .param("country", update.country()).param("fyStartMonth", update.fyStartMonth())
                .param("id", user.id())
                .update();
        if (update.currency() != null) {
            // Money is kept in the space's currency. A space with nothing in it yet simply follows the new choice; one with
            // accounts or entries keeps its currency, so nothing already recorded changes meaning.
            jdbc.sql("""
                            update spaces s set currency = :currency
                            where s.id = :space and s.currency <> :currency
                              and not exists (select 1 from money_accounts a where a.space_id = s.id and a.kind <> 'OWED')
                              and not exists (select 1 from transactions t where t.space_id = s.id)
                            """).param("currency", update.currency()).param("space", user.personalSpaceId()).update();
        }
        return get(user);
    }

    record Row(String displayName, String locale, String timeZone, String currency, int budgetStartDay, String preferences, String upiId,
               String country, int fyStartMonth) {
    }
}
