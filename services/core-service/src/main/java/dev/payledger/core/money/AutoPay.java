package dev.payledger.core.money;

import dev.payledger.core.notify.Inbox;
import dev.payledger.core.user.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Records automatic bills and savings on their due date: if you already entered the payment yourself (a matching
 * expense or transfer), that one is linked instead, so nothing is counted twice. Looks back a week, so a day the
 * laptop or server was off is caught up; never before the day it was turned on.
 */
@Service
public class AutoPay {

    private static final Logger log = LoggerFactory.getLogger(AutoPay.class);
    static final int CATCH_UP_DAYS = 7;

    private final JdbcClient jdbc;
    private final CommitmentService commitments;
    private final Inbox inbox;
    private final TransactionTemplate tx;

    AutoPay(JdbcClient jdbc, CommitmentService commitments, Inbox inbox, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.commitments = commitments;
        this.inbox = inbox;
        this.tx = tx;
    }

    /** Early, before the morning reminders, so they already see it paid. Again at noon in case the first was missed. */
    @Scheduled(cron = "${payledger.autopay.cron:0 30 6,12 * * *}", zone = "Asia/Kolkata")
    void everyMorning() {
        int n = runAll();
        if (n > 0) {
            log.info("Automatic bills: {} recorded or linked", n);
        }
    }

    private record Auto(UUID id, UUID spaceId, String name, LocalDate autoSince, UUID userId, String subject, String email,
                        String displayName, String timeZone) {
    }

    /** @return how many due dates were recorded or linked */
    public int runAll() {
        List<Auto> autos = jdbc.sql("""
                        select c.id, c.space_id, c.name, c.auto_since, u.id as user_id, u.subject, u.email, u.display_name, u.time_zone
                        from commitments c join spaces s on s.id = c.space_id join users u on u.id = s.created_by
                        where c.auto and u.disabled_at is null
                        """).query(Auto.class).list();
        int done = 0;
        for (Auto a : autos) {
            CurrentUser user = new CurrentUser(a.userId(), a.subject(), a.email(), a.displayName(), a.spaceId());
            LocalDate today = LocalDate.now(ZoneId.of(a.timeZone()));
            LocalDate from = today.minusDays(CATCH_UP_DAYS);
            if (a.autoSince() != null && a.autoSince().isAfter(from)) {
                from = a.autoSince();
            }
            for (CommitmentService.Due d : commitments.due(user, a.spaceId(), from, today)) {
                if (!d.commitmentId().equals(a.id()) || d.paid()) {
                    continue;
                }
                try {
                    Integer one = tx.execute(s -> {
                        CommitmentService.Due paid = d.suggestion() != null
                                ? commitments.link(user, a.spaceId(), a.id(), d.dueDate(), d.suggestion().transactionId())
                                : commitments.pay(user, a.spaceId(), a.id(), new CommitmentRequests.Pay(d.dueDate(), null, null));
                        inbox.notify(a.userId(), Inbox.Kind.AUTO_PAID,
                                (d.suggestion() != null ? "Matched: " : "Recorded: ") + a.name() + " " + dev.payledger.core.notify.Reminders.money(paid.amount()),
                                d.suggestion() != null ? "Your entry \"" + d.suggestion().description() + "\" paid it." : "Due " + d.dueDate() + ", recorded automatically. Change or delete it like any entry.",
                                "/transactions?q=" + java.net.URLEncoder.encode(a.name(), java.nio.charset.StandardCharsets.UTF_8), null,
                                "auto:" + a.id() + ":" + d.dueDate(), null);
                        return 1;
                    });
                    done += one == null ? 0 : one;
                } catch (RuntimeException e) {
                    // Paid meanwhile (a tap in the app), or an archived account: leave it for the person.
                    log.warn("Automatic bill {} on {} skipped: {}", a.id(), d.dueDate(), e.getMessage());
                }
            }
        }
        return done;
    }
}
