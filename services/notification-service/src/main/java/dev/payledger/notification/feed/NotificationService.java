package dev.payledger.notification.feed;

import dev.payledger.notification.events.LedgerEntryPosted;
import dev.payledger.notification.events.LedgerEntryPosted.Leg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Currency;
import java.util.List;

@Service
public class NotificationService {

    private final JdbcClient jdbc;

    public NotificationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Writes one notification per user wallet in the event (system accounts are internal).
     * Safe to call any number of times with the same event: duplicates hit the unique
     * (event_id, account_id) constraint and are skipped.
     *
     * @return how many notifications were actually created (0 for a redelivered event)
     */
    @Transactional
    public int record(LedgerEntryPosted event) {
        int created = 0;
        for (Leg leg : event.postings()) {
            if (!leg.isUserWallet()) {
                continue;
            }
            created += jdbc.sql("""
                            insert into notifications (event_id, entry_id, account_id, owner_id, entry_type, amount,
                                                       currency, balance_after, message, occurred_at)
                            values (:eventId, :entryId, :accountId, :ownerId, :entryType, :amount,
                                    :currency, :balanceAfter, :message, :occurredAt)
                            on conflict (event_id, account_id) do nothing
                            """)
                    .param("eventId", event.eventId())
                    .param("entryId", event.entryId())
                    .param("accountId", leg.accountId())
                    .param("ownerId", leg.ownerId())
                    .param("entryType", event.entryType())
                    .param("amount", leg.amount())
                    .param("currency", event.currency())
                    .param("balanceAfter", leg.balanceAfter())
                    .param("message", message(event, leg))
                    .param("occurredAt", Timestamp.from(event.occurredAt()))
                    .update();
        }
        return created;
    }

    @Transactional(readOnly = true)
    public List<Notification> feed(String ownerId, Long beforeId, int limit) {
        return jdbc.sql("""
                        select id, entry_id, account_id, owner_id, entry_type, amount, currency, balance_after,
                               message, occurred_at
                        from notifications
                        where owner_id = :ownerId and (cast(:beforeId as bigint) is null or id < :beforeId)
                        order by id desc
                        limit :limit
                        """)
                .param("ownerId", ownerId)
                .param("beforeId", beforeId)
                .param("limit", limit)
                .query(Notification.class)
                .list();
    }

    static String message(LedgerEntryPosted event, Leg leg) {
        String amount = money(Math.abs(leg.amount()), event.currency());
        String balance = money(leg.balanceAfter(), event.currency());
        String counterparty = event.postings().stream()
                .filter(other -> other.isUserWallet() && !other.accountId().equals(leg.accountId()))
                .map(Leg::ownerId)
                .findFirst()
                .orElse(null);
        String what = switch (event.entryType()) {
            case "DEPOSIT" -> "Deposit of " + amount + " received";
            case "TRANSFER" -> leg.amount() < 0
                    ? "You sent " + amount + (counterparty != null ? " to " + counterparty : "")
                    : "You received " + amount + (counterparty != null ? " from " + counterparty : "");
            default -> (leg.amount() < 0 ? "Debit of " : "Credit of ") + amount;
        };
        return what + ". Balance: " + balance + ".";
    }

    /** 2500 USD -> "25.00 USD"; uses the currency's own number of decimals (JPY has none). */
    static String money(long minorUnits, String currency) {
        int digits = Math.max(Currency.getInstance(currency).getDefaultFractionDigits(), 0);
        return BigDecimal.valueOf(minorUnits, digits).toPlainString() + " " + currency;
    }
}
