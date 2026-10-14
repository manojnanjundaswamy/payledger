package dev.payledger.core.notify;

import dev.payledger.core.common.Uuid7;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Queues emails (docs/notifications.md). Call it inside the transaction that caused the email: the row commits with
 * the change, or not at all, and {@link EmailSender} delivers it afterwards. A dedupe key makes queuing idempotent.
 */
@Service
public class Notifications {

    /** What people can turn off. Account emails (welcome, sign-in, invites to people without an account) always go. */
    public enum Kind {
        BILL_REMINDERS(true), BUDGET_ALERTS(true), GROUP_ACTIVITY(true), WEEKLY_RECAP(true), PRODUCT_NEWS(false);

        final boolean onByDefault;

        Kind(boolean onByDefault) {
            this.onByDefault = onByDefault;
        }
    }

    private static final TypeReference<Map<String, Boolean>> PREFS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final String frontendUrl;

    Notifications(JdbcClient jdbc, JsonMapper json, @Value("${payledger.frontend-url:http://localhost:5173}") String frontendUrl) {
        this.jdbc = jdbc;
        this.json = json;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    /** A link into the web app, e.g. url("/plan"). */
    public String url(String path) {
        return frontendUrl + path;
    }

    public String settingsUrl() {
        return url("/settings?tab=notifications");
    }

    /** Queues an email to a user, unless they turned that kind off. Returns false if it was skipped or already queued. */
    public boolean toUser(UUID userId, Kind kind, String dedupeKey, Email email) {
        record Row(String email, String emailPrefs, boolean disabled) {
        }
        Row row = jdbc.sql("select email, email_prefs::text as email_prefs, disabled_at is not null as disabled from users where id = :id")
                .param("id", userId).query(Row.class).optional().orElse(null);
        if (row == null || row.disabled() || (kind != null && !prefs(row.emailPrefs()).get(kind))) {
            return false;
        }
        return queue(userId, kind == null ? "ACCOUNT" : kind.name(), row.email(), dedupeKey, email);
    }

    /** An email to an address that may not have an account yet (an invite). Always sent. */
    public boolean toAddress(String address, String kind, String dedupeKey, Email email) {
        return queue(null, kind, address, dedupeKey, email);
    }

    public Map<Kind, Boolean> prefs(UUID userId) {
        return prefs(jdbc.sql("select email_prefs::text from users where id = :id").param("id", userId).query(String.class).single());
    }

    public Map<Kind, Boolean> setPrefs(UUID userId, Map<Kind, Boolean> changes) {
        Map<Kind, Boolean> merged = prefs(userId);
        merged.putAll(changes);
        Map<String, Boolean> stored = new java.util.LinkedHashMap<>();
        merged.forEach((k, v) -> stored.put(k.name(), v));
        jdbc.sql("update users set email_prefs = cast(:prefs as jsonb), updated_at = now() where id = :id")
                .param("prefs", json.writeValueAsString(stored)).param("id", userId).update();
        return merged;
    }

    private Map<Kind, Boolean> prefs(String stored) {
        Map<String, Boolean> raw = json.readValue(stored == null ? "{}" : stored, PREFS);
        Map<Kind, Boolean> result = new EnumMap<>(Kind.class);
        for (Kind k : Kind.values()) {
            result.put(k, raw.getOrDefault(k.name(), k.onByDefault));
        }
        return result;
    }

    private boolean queue(UUID userId, String kind, String to, String dedupeKey, Email email) {
        return jdbc.sql("""
                        insert into email_outbox (id, user_id, kind, to_email, subject, body_html, body_text, dedupe_key)
                        values (:id, :userId, :kind, :to, :subject, :html, :text, :dedupe)
                        on conflict (dedupe_key) do nothing
                        """)
                .param("id", Uuid7.next()).param("userId", userId).param("kind", kind).param("to", to)
                .param("subject", email.subject()).param("html", email.html()).param("text", email.text()).param("dedupe", dedupeKey)
                .update() > 0;
    }
}
