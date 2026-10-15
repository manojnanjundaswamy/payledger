package dev.payledger.core.progress;

import dev.payledger.core.notify.Inbox;
import dev.payledger.core.user.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Every hour, for people whose local time is 8 in the evening: badges earned since they last looked are announced,
 * and a streak of 3 days or more that hasn't been kept today gets one friendly nudge. Once a day each (dedupe keys).
 */
@Component
class ProgressNudges {

    private static final Logger log = LoggerFactory.getLogger(ProgressNudges.class);

    private final JdbcClient jdbc;
    private final ProgressService progress;
    private final Inbox inbox;
    private final TransactionTemplate tx;

    ProgressNudges(JdbcClient jdbc, ProgressService progress, Inbox inbox, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.progress = progress;
        this.inbox = inbox;
        this.tx = tx;
    }

    @Scheduled(cron = "${payledger.progress.cron:0 5 * * * *}")
    void hourly() {
        int nudged = 0;
        for (CurrentUser u : jdbc.sql("""
                        select u.id, u.subject, u.email, u.display_name, s.id as personal_space_id
                        from users u join spaces s on s.created_by = u.id and s.kind = 'PERSONAL'
                        where u.disabled_at is null and u.last_active_on > current_date - 30
                          and extract(hour from now() at time zone u.time_zone) = 20
                        """).query(CurrentUser.class).list()) {
            try {
                Boolean sent = tx.execute(s -> nudge(u));
                nudged += Boolean.TRUE.equals(sent) ? 1 : 0;
            } catch (RuntimeException e) {
                log.warn("Progress nudge skipped for {}: {}", u.id(), e.getMessage());
            }
        }
        if (nudged > 0) {
            log.info("Streak nudges: {}", nudged);
        }
    }

    boolean nudge(CurrentUser u) {
        progress.progress(u);
        ZoneId zone = ZoneId.of(jdbc.sql("select time_zone from users where id = :u").param("u", u.id()).query(String.class).single());
        LocalDate today = ZonedDateTime.now(zone).toLocalDate();
        ProgressService.Streak streak = progress.streak(u, zone, today);
        if (streak.today() || streak.current() < 3) {
            return false;
        }
        return inbox.notify(u.id(), Inbox.Kind.ACHIEVEMENT, "Keep your " + streak.current() + "-day streak going",
                "Record today's spending before midnight. A minute is enough.", "/transactions?add=EXPENSE", null,
                "streak:" + u.id() + ":" + today, null);
    }

}
