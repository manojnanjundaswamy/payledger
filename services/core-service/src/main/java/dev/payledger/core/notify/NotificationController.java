package dev.payledger.core.notify;

import dev.payledger.core.common.ApiException;
import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/** Your email settings, and a test email to check that mail reaches you. */
@RestController
class NotificationController {

    private final Notifications notifications;
    private final JdbcClient jdbc;

    NotificationController(Notifications notifications, JdbcClient jdbc) {
        this.notifications = notifications;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/core/me/notifications")
    @Operation(summary = "Which emails you get: BILL_REMINDERS, BUDGET_ALERTS, GROUP_ACTIVITY, PRODUCT_NEWS")
    Map<Notifications.Kind, Boolean> get(CurrentUser user) {
        return notifications.prefs(user.id());
    }

    @PatchMapping("/api/core/me/notifications")
    @Operation(summary = "Turn kinds of email on or off; kinds left out stay as they are")
    Map<Notifications.Kind, Boolean> update(CurrentUser user, @RequestBody Map<Notifications.Kind, Boolean> changes) {
        return notifications.setPrefs(user.id(), changes);
    }

    @PostMapping("/api/core/me/notifications/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Send yourself a test email (at most one a minute); 429 too-soon")
    void test(CurrentUser user) {
        // One per minute: the dedupe key carries the minute.
        long minute = Instant.now().getEpochSecond() / 60;
        boolean queued = notifications.toUser(user.id(), null, "test:" + user.id() + ":" + minute,
                Email.titled("Test email from PayLedger")
                        .heading("Email works")
                        .para("If you can read this, PayLedger can reach your inbox. Bill reminders and budget alerts will arrive here.")
                        .button("Back to PayLedger", notifications.url("/dashboard"))
                        .footer("You asked for this test email from your settings.", notifications.settingsUrl()));
        if (!queued) {
            throw new TooSoonException();
        }
    }

    /** The latest emails to you: what was sent, and whether it went. */
    @GetMapping("/api/core/me/notifications/sent")
    @Operation(summary = "Your last 20 emails: subject, kind, when queued, when sent, or the error if it failed")
    java.util.List<Map<String, Object>> sent(CurrentUser user) {
        return jdbc.sql("""
                        select kind, subject, created_at, sent_at, attempts, last_error from email_outbox
                        where user_id = :id order by created_at desc limit 20
                        """)
                .param("id", user.id()).query().listOfRows();
    }

    static class TooSoonException extends ApiException {
        TooSoonException() {
            super(HttpStatus.TOO_MANY_REQUESTS, "too-soon", "a test email was just sent; wait a minute");
        }
    }
}
