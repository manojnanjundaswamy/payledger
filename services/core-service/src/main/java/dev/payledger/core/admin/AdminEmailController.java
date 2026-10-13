package dev.payledger.core.admin;

import dev.payledger.core.admin.StaffRole.Permission;
import dev.payledger.core.notify.Reminders;
import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Email delivery for staff: what went out and what failed (subjects only, never the body), and a manual reminder run. */
@RestController
@RequestMapping("/api/core/admin/emails")
class AdminEmailController {

    private final StaffService staff;
    private final JdbcClient jdbc;
    private final Reminders reminders;
    private final AuditLog audit;

    AdminEmailController(StaffService staff, JdbcClient jdbc, Reminders reminders, AuditLog audit) {
        this.staff = staff;
        this.jdbc = jdbc;
        this.reminders = reminders;
        this.audit = audit;
    }

    @GetMapping
    @Operation(summary = "Staff (users support): the last 100 emails with their status; counts of pending and failed")
    Map<String, Object> list(CurrentUser user) {
        staff.require(user, Permission.USERS_SUPPORT);
        List<Map<String, Object>> rows = jdbc.sql("""
                        select id, kind, to_email, subject, created_at, sent_at, attempts, last_error from email_outbox
                        order by created_at desc limit 100
                        """).query().listOfRows();
        Map<String, Object> counts = jdbc.sql("""
                        select count(*) filter (where sent_at is null and attempts < 6) as pending,
                               count(*) filter (where sent_at is null and attempts >= 6) as failed,
                               count(*) filter (where sent_at > now() - interval '1 day') as sent24h
                        from email_outbox
                        """).query().singleRow();
        return Map.of("counts", counts, "emails", rows);
    }

    @PostMapping("/run-reminders")
    @Operation(summary = "Staff (settings manage): run the morning reminders now (safe to repeat: nothing is sent twice)")
    Reminders.Run run(CurrentUser user) {
        staff.require(user, Permission.SETTINGS_MANAGE);
        Reminders.Run run = reminders.runAll();
        audit.record(user.id(), "reminders.run", null, Map.of("users", run.users(), "bills", run.billEmails(), "budgets", run.budgetEmails()));
        return run;
    }
}
