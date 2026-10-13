package dev.payledger.core.admin;

import dev.payledger.core.common.Uuid7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Every admin write, recorded in the same transaction as the write itself. Append-only. */
@Component
public class AuditLog {

    /**
     * @param actor   who did it (name and email at the time of reading)
     * @param target  whom it was done to, if a user
     * @param details what changed, e.g. {"until": "2026-12-31"}
     */
    public record Entry(UUID id, Instant at, String action, UUID actorId, String actorEmail, UUID targetId, String targetEmail,
                        Map<String, Object> details) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    AuditLog(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(UUID actorId, String action, UUID targetUserId, Map<String, ?> details) {
        jdbc.sql("""
                        insert into admin_audit (id, actor_id, action, target_user_id, details)
                        values (:id, :actor, :action, :target, cast(:details as jsonb))
                        """)
                .param("id", Uuid7.next()).param("actor", actorId).param("action", action)
                .param("target", targetUserId).param("details", json.writeValueAsString(details))
                .update();
    }

    /** Newest first; {@code targetUserId} narrows it to what was done to one user. */
    public List<Entry> list(UUID targetUserId, int limit, int offset) {
        return jdbc.sql("""
                        select a.id, a.at, a.action, a.actor_id, actor.email as actor_email, a.target_user_id,
                               target.email as target_email, a.details::text as details
                        from admin_audit a
                        join users actor on actor.id = a.actor_id
                        left join users target on target.id = a.target_user_id
                        where cast(:target as uuid) is null or a.target_user_id = :target
                        order by a.at desc, a.id desc
                        limit :limit offset :offset
                        """)
                .param("target", targetUserId).param("limit", limit).param("offset", offset)
                .query((rs, i) -> new Entry(rs.getObject("id", UUID.class), rs.getTimestamp("at").toInstant(), rs.getString("action"),
                        rs.getObject("actor_id", UUID.class), rs.getString("actor_email"), rs.getObject("target_user_id", UUID.class),
                        rs.getString("target_email"), readDetails(rs.getString("details"))))
                .list();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readDetails(String text) {
        return json.readValue(text, Map.class);
    }
}
