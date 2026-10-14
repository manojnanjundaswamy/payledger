package dev.payledger.core.notify;

import dev.payledger.core.common.Uuid7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The in-app inbox (the bell): everything that involves a user. Each item is also emailed, unless the user turned that
 * kind of email off. Call it inside the transaction of the change it is about, so the two commit together.
 */
@Service
public class Inbox {

    /** What an item is about; each maps to the email setting that controls its email. */
    public enum Kind {
        GROUP_EXPENSE(Notifications.Kind.GROUP_ACTIVITY), SETTLEMENT(Notifications.Kind.GROUP_ACTIVITY),
        GROUP_MEMBER(Notifications.Kind.GROUP_ACTIVITY), BILL_DUE(Notifications.Kind.BILL_REMINDERS),
        BILL_OVERDUE(Notifications.Kind.BILL_REMINDERS), BUDGET(Notifications.Kind.BUDGET_ALERTS),
        /** Money lent or borrowed is due back (People). */
        LOAN_DUE(Notifications.Kind.BILL_REMINDERS),
        /** A bill or saving recorded by itself on its due date. In the app only. */
        AUTO_PAID(null),
        /** Bank alerts forwarded or Gmail's forwarding code arrived (Auto-capture). In the app only. */
        CAPTURED(null),
        /** Monday's look back at the week. */
        RECAP(Notifications.Kind.WEEKLY_RECAP),
        /** Security: a sign-in. In the app only; an email for every sign-in would be noise. */
        SIGN_IN(null);

        final Notifications.Kind email;

        Kind(Notifications.Kind email) {
            this.email = email;
        }
    }

    public record Item(UUID id, String kind, String title, String body, String link, Instant createdAt, Instant readAt) {
    }

    public record Page(long unread, List<Item> items) {
    }

    private final JdbcClient jdbc;
    private final Notifications notifications;

    Inbox(JdbcClient jdbc, Notifications notifications) {
        this.jdbc = jdbc;
        this.notifications = notifications;
    }

    /**
     * Adds an item for a user (and queues its email).
     *
     * @param link       a path in the web app, like "/groups/…"
     * @param dedupeKey  optional: the same key is only ever added once (reminders)
     * @param email      the email to send, or null to build one from the title and body
     * @return false if it was a duplicate
     */
    public boolean notify(UUID userId, Kind kind, String title, String body, String link, UUID actorId, String dedupeKey, Email email) {
        UUID id = Uuid7.next();
        int added = jdbc.sql("""
                        insert into inbox (id, user_id, kind, title, body, link, actor_id, dedupe_key)
                        values (:id, :userId, :kind, :title, :body, :link, :actor, :dedupe)
                        on conflict (dedupe_key) do nothing
                        """)
                .param("id", id).param("userId", userId).param("kind", kind.name()).param("title", cut(title, 200))
                .param("body", body == null ? null : cut(body, 500)).param("link", link).param("actor", actorId).param("dedupe", dedupeKey)
                .update();
        if (added == 0 || kind.email == null) {
            return added > 0;
        }
        Email message = email != null ? email : Email.titled(title).heading(title);
        if (email == null) {
            if (body != null) {
                message.para(body);
            }
            if (link != null) {
                message.button("Open in PayLedger", notifications.url(link));
            }
            message.footer("You got this because it involves you on PayLedger.", notifications.settingsUrl());
        }
        notifications.toUser(userId, kind.email, "inbox:" + id, message);
        return true;
    }

    /** Shortcut for activity: no dedupe key, the email built from the item. */
    public void notify(UUID userId, Kind kind, String title, String body, String link, UUID actorId) {
        notify(userId, kind, title, body, link, actorId, null, null);
    }

    public Page page(UUID userId, int limit) {
        long unread = unread(userId);
        List<Item> items = jdbc.sql("""
                        select id, kind, title, body, link, created_at, read_at from inbox
                        where user_id = :id order by created_at desc, id desc limit :limit
                        """)
                .param("id", userId).param("limit", limit).query(Item.class).list();
        return new Page(unread, items);
    }

    public long unread(UUID userId) {
        return jdbc.sql("select count(*) from inbox where user_id = :id and read_at is null").param("id", userId).query(Long.class).single();
    }

    /** Marks some (or, with null, all) of the user's items read. Others' items are untouched whatever ids are sent. */
    public long markRead(UUID userId, List<UUID> ids) {
        if (ids == null) {
            jdbc.sql("update inbox set read_at = now() where user_id = :id and read_at is null").param("id", userId).update();
        } else if (!ids.isEmpty()) {
            jdbc.sql("update inbox set read_at = now() where user_id = :id and read_at is null and id in (:ids)")
                    .param("id", userId).param("ids", ids).update();
        }
        return unread(userId);
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
