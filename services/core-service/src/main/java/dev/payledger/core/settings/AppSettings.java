package dev.payledger.core.settings;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Settings staff change at runtime: Free's limits, the trial length and the announcement. Read on every limit check,
 * so they are cached; a change is seen at once on the instance that made it and within {@link #REFRESH} elsewhere.
 */
@Component
public class AppSettings {

    static final Duration REFRESH = Duration.ofSeconds(30);

    /** The built-in Free limits (docs/plan/market-and-pricing.md), by limit key. */
    public static final Map<String, Integer> DEFAULT_FREE_LIMITS = defaults();
    public static final int DEFAULT_TRIAL_DAYS = 30;

    /** @param tone INFO or WARNING; {@code until} the last day it shows (null: until removed) */
    public record Announcement(String message, String tone, LocalDate until) {
    }

    /** @param trialDays 0 turns the trial off for people who haven't used it */
    public record Values(Map<String, Integer> freeLimits, int trialDays, Announcement announcement) {
    }

    private record Cached(Values values, Instant loaded) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private volatile Cached cached;

    AppSettings(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Values values() {
        Cached c = cached;
        if (c == null || c.loaded().isBefore(Instant.now().minus(REFRESH))) {
            c = new Cached(load(), Instant.now());
            cached = c;
        }
        return c.values();
    }

    public int freeLimit(String key) {
        return values().freeLimits().getOrDefault(key, DEFAULT_FREE_LIMITS.get(key));
    }

    public int trialDays() {
        return values().trialDays();
    }

    /** Saves all three; call inside the caller's transaction (it audits the change). */
    public void save(Values values, UUID by) {
        put("free-limits", values.freeLimits(), by);
        put("trial-days", values.trialDays(), by);
        if (values.announcement() == null) {
            jdbc.sql("delete from app_settings where key = 'announcement'").update();
        } else {
            put("announcement", values.announcement(), by);
        }
        cached = null;
    }

    /** Forget the cache, after a change commits, so no instance keeps an older read for long. */
    public void refresh() {
        cached = null;
    }

    private void put(String key, Object value, UUID by) {
        jdbc.sql("""
                        insert into app_settings (key, value, updated_by) values (:key, cast(:value as jsonb), :by)
                        on conflict (key) do update set value = excluded.value, updated_by = excluded.updated_by, updated_at = now()
                        """)
                .param("key", key).param("value", json.writeValueAsString(value)).param("by", by).update();
    }

    private Values load() {
        Map<String, String> rows = new LinkedHashMap<>();
        jdbc.sql("select key, value::text as value from app_settings")
                .query((rs, i) -> rows.put(rs.getString("key"), rs.getString("value"))).list();
        Map<String, Integer> limits = new LinkedHashMap<>(DEFAULT_FREE_LIMITS);
        if (rows.containsKey("free-limits")) {
            json.readValue(rows.get("free-limits"), Map.class).forEach((k, v) -> {
                if (limits.containsKey(String.valueOf(k)) && v instanceof Number n) {
                    limits.put(String.valueOf(k), n.intValue());
                }
            });
        }
        int trialDays = rows.containsKey("trial-days") ? Integer.parseInt(rows.get("trial-days").strip()) : DEFAULT_TRIAL_DAYS;
        Announcement announcement = rows.containsKey("announcement") ? json.readValue(rows.get("announcement"), Announcement.class) : null;
        return new Values(java.util.Collections.unmodifiableMap(limits), trialDays, announcement);
    }

    private static Map<String, Integer> defaults() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("accounts", 5);
        m.put("budgets", 5);
        m.put("bills", 5);
        m.put("big-costs", 1);
        m.put("imports", 3);
        m.put("goals", 1);
        return java.util.Collections.unmodifiableMap(m);
    }
}
