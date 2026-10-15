package dev.payledger.core.group;

import dev.payledger.core.common.Uuid7;
import dev.payledger.core.user.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A group's history: every change, who made it and when, with its details (V20). Written in the same transaction as
 * the change, so the history never claims something that didn't happen.
 */
@Component
public class GroupHistory {

    public enum Action {
        GROUP_CREATED, GROUP_UPDATED, MEMBER_ADDED, MEMBER_JOINED, MEMBER_REMOVED, MEMBER_LEFT, LINK_RESET,
        EXPENSE_ADDED, EXPENSE_EDITED, EXPENSE_DELETED, SETTLEMENT_RECORDED, SETTLEMENT_UNDONE
    }

    /** @param you the signed-in user made this change */
    public record Event(UUID id, String action, String actorName, boolean you, Instant at, Map<String, Object> details) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    GroupHistory(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    void record(UUID groupId, CurrentUser actor, Action action, Map<String, ?> details) {
        record(groupId, actor == null ? null : actor.id(), actor == null ? null : actor.displayName(), action, details);
    }

    void record(UUID groupId, UUID actorId, String actorName, Action action, Map<String, ?> details) {
        // The name in this group if they have one there (what the others know them by), else their profile name.
        String name = actorId == null ? actorName : jdbc.sql("""
                        select coalesce((select display_name from group_members where group_id = :g and user_id = :u order by created_at limit 1),
                                        (select display_name from users where id = :u), cast(:fallback as text))
                        """).param("g", groupId).param("u", actorId).param("fallback", actorName).query(String.class).single();
        jdbc.sql("insert into group_events (id, group_id, actor_user_id, actor_name, action, details) values (:id, :g, :u, :name, :action, cast(:details as jsonb))")
                .param("id", Uuid7.next()).param("g", groupId).param("u", actorId).param("name", name)
                .param("action", action.name()).param("details", json.writeValueAsString(details))
                .update();
    }

    /** Newest first, at most {@code limit}. Access is checked by the caller. */
    @SuppressWarnings("unchecked")
    List<Event> list(UUID groupId, UUID me, int limit) {
        record Row(UUID id, String action, String actorName, UUID actorUserId, Instant at, String details) {
        }
        return jdbc.sql("""
                        select id, action, actor_name, actor_user_id, at, details::text as details from group_events
                        where group_id = :g order by at desc, id desc limit :limit
                        """).param("g", groupId).param("limit", limit)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("action"), rs.getString("actor_name"),
                        rs.getObject("actor_user_id", UUID.class), rs.getTimestamp("at").toInstant(), rs.getString("details")))
                .list().stream()
                .map(r -> new Event(r.id(), r.action(), r.actorName(), me != null && me.equals(r.actorUserId()), r.at(), json.readValue(r.details(), LinkedHashMap.class)))
                .toList();
    }

    /** Builds a details map, leaving out nulls (Map.of refuses them). */
    static Map<String, Object> details(Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            if (keysAndValues[i + 1] != null) {
                m.put((String) keysAndValues[i], keysAndValues[i + 1]);
            }
        }
        return m;
    }
}
