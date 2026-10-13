package dev.payledger.core.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Notes that a user was active today, for daily/weekly/monthly active users in the admin console.
 * At most one write per user per day per instance: the day already recorded is remembered in memory,
 * and the insert is idempotent, so several instances (or a restart) only repeat a harmless no-op.
 */
@Component
public class ActivityTracker {

    private final JdbcClient jdbc;
    private final ZoneId zone;
    private final Clock clock;
    private final Map<UUID, LocalDate> recorded = new ConcurrentHashMap<>();

    ActivityTracker(JdbcClient jdbc, @Value("${payledger.business-zone:Asia/Kolkata}") ZoneId zone) {
        this.jdbc = jdbc;
        this.zone = zone;
        this.clock = Clock.system(zone);
    }

    public ZoneId zone() {
        return zone;
    }

    void seen(UUID userId) {
        LocalDate today = LocalDate.now(clock);
        if (today.equals(recorded.get(userId))) {
            return;
        }
        jdbc.sql("insert into user_activity_days (user_id, day) values (:id, :day) on conflict do nothing")
                .param("id", userId).param("day", today).update();
        jdbc.sql("update users set last_active_on = :day where id = :id and (last_active_on is null or last_active_on < :day)")
                .param("id", userId).param("day", today).update();
        recorded.put(userId, today);
        if (recorded.size() > 100_000) {
            // Yesterday's entries are useless; dropping everything costs at most one extra no-op write per user.
            recorded.clear();
        }
    }
}
