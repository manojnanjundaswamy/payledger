package dev.payledger.core.notify;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Delivers queued emails over SMTP (Mailpit locally; a provider such as Amazon SES, Brevo or Gmail in production,
 * set with SMTP_* variables). Each row is claimed with SKIP LOCKED, so several instances never send the same email.
 * A failure is retried with a growing wait (1, 2, 4, 8, 16 minutes), then given up and kept with its error.
 */
@Component
class EmailSender {

    static final int MAX_ATTEMPTS = 6;
    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

    private final JdbcClient jdbc;
    private final JavaMailSender mail;
    private final TransactionTemplate tx;
    private final String from;
    private final String fromName;
    private final boolean enabled;

    EmailSender(JdbcClient jdbc, JavaMailSender mail, TransactionTemplate tx,
                @Value("${payledger.mail.from:no-reply@payledger.local}") String from,
                @Value("${payledger.mail.from-name:PayLedger}") String fromName,
                @Value("${payledger.mail.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.mail = mail;
        this.tx = tx;
        this.from = from;
        this.fromName = fromName;
        this.enabled = enabled;
    }

    private record Pending(UUID id, String toEmail, String subject, String bodyHtml, String bodyText, int attempts) {
    }

    @Scheduled(fixedDelayString = "${payledger.mail.poll-interval:10s}", initialDelayString = "5s")
    void sendPending() {
        if (!enabled) {
            return;
        }
        for (int batch = 0; batch < 10; batch++) {
            Integer sent = tx.execute(status -> {
                List<Pending> rows = jdbc.sql("""
                                select id, to_email, subject, body_html, body_text, attempts from email_outbox
                                where sent_at is null and attempts < :max and send_after <= now()
                                order by created_at limit 20 for update skip locked
                                """)
                        .param("max", MAX_ATTEMPTS).query(Pending.class).list();
                rows.forEach(this::deliver);
                return rows.size();
            });
            if (sent == null || sent < 20) {
                return;
            }
        }
    }

    private void deliver(Pending p) {
        try {
            MimeMessage message = mail.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(from, fromName, StandardCharsets.UTF_8.name()));
            helper.setTo(p.toEmail());
            helper.setSubject(p.subject());
            helper.setText(p.bodyText(), p.bodyHtml());
            mail.send(message);
            jdbc.sql("update email_outbox set sent_at = now(), attempts = attempts + 1, last_error = null where id = :id")
                    .param("id", p.id()).update();
        } catch (Exception e) {
            String error = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("Email {} to {} not sent (attempt {}): {}", p.id(), p.toEmail(), p.attempts() + 1, error);
            jdbc.sql("""
                            update email_outbox set attempts = attempts + 1, last_error = :error,
                                send_after = now() + make_interval(mins => cast(power(2, attempts) as int))
                            where id = :id
                            """)
                    .param("id", p.id()).param("error", error.length() > 500 ? error.substring(0, 500) : error).update();
        }
    }
}
