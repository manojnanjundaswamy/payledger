package dev.payledger.core.money;

import dev.payledger.core.common.InvalidRequestException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * What to find among a space's transactions: the same filter for the list, its totals and the exports, so a file
 * holds exactly what the screen showed. Every part is optional.
 *
 * @param q         words in the description, note, tags, category or account name; a number also finds that exact amount
 * @param kind      EXPENSE, INCOME or TRANSFER
 * @param minAmount paise, inclusive
 * @param maxAmount paise, inclusive
 * @param tag       one tag, exactly
 * @param person    who it was with, exactly (ignoring case)
 */
public record TransactionFilter(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, UUID accountId, UUID categoryId, String kind, String q,
                                Long minAmount, Long maxAmount, String tag, UUID goalId, String person) {

    public static final TransactionFilter NONE = new TransactionFilter(null, null, null, null, null, null, null, null, null, null, null);

    /** Without a person (older callers). */
    public TransactionFilter(LocalDate from, LocalDate to, UUID accountId, UUID categoryId, String kind, String q,
                             Long minAmount, Long maxAmount, String tag, UUID goalId) {
        this(from, to, accountId, categoryId, kind, q, minAmount, maxAmount, tag, goalId, null);
    }

    // Only tidies: Spring builds this from query parameters, and an exception here would surface as a 500.
    public TransactionFilter {
        kind = kind == null || kind.isBlank() ? null : kind.toUpperCase(Locale.ROOT);
        q = q == null || q.isBlank() ? null : q.strip();
        tag = tag == null || tag.isBlank() ? null : tag.strip();
        person = person == null || person.isBlank() ? null : person.strip();
    }

    /** This filter, or 422 with a stable code when it can't match anything sensible. */
    TransactionFilter checked() {
        if (from != null && to != null && to.isBefore(from)) {
            throw new InvalidRequestException("invalid-range", "to is before from");
        }
        if (minAmount != null && maxAmount != null && maxAmount < minAmount) {
            throw new InvalidRequestException("invalid-range", "maxAmount is below minAmount");
        }
        if (kind != null && !kind.matches("EXPENSE|INCOME|TRANSFER")) {
            throw new InvalidRequestException("invalid-kind", "kind must be EXPENSE, INCOME or TRANSFER");
        }
        if (q != null && q.length() > 100) {
            throw new InvalidRequestException("invalid-search", "search is at most 100 characters");
        }
        return this;
    }

    /** SQL conditions on the transactions alias {@code t}; bind them with {@link #bind}. */
    static final String WHERE = """
              and (cast(:from as date) is null or t.occurred_on >= :from)
              and (cast(:to as date) is null or t.occurred_on <= :to)
              and (cast(:accountId as uuid) is null or t.account_id = :accountId or t.to_account_id = :accountId)
              -- A category includes its subcategories, as its totals do in reports and budgets.
              and (cast(:categoryId as uuid) is null or t.category_id = :categoryId
                   or t.category_id in (select id from categories where parent_id = :categoryId))
              and (cast(:kind as text) is null or t.kind = :kind)
              and (cast(:minAmount as bigint) is null or t.amount >= :minAmount)
              and (cast(:maxAmount as bigint) is null or t.amount <= :maxAmount)
              and (cast(:tag as text) is null or :tag = any (t.tags))
              and (cast(:goalId as uuid) is null or t.goal_id = :goalId)
              and (cast(:person as text) is null or lower(t.counterparty) = lower(:person))
              and (cast(:q as text) is null
                   or t.description ilike :q escape '\\' or t.note ilike :q escape '\\' or t.counterparty ilike :q escape '\\'
                   or array_to_string(t.tags, ' ') ilike :q escape '\\'
                   or t.amount = cast(:qAmount as bigint)
                   or exists (select 1 from categories c where c.id = t.category_id and c.name ilike :q escape '\\')
                   or exists (select 1 from money_accounts a where a.id in (t.account_id, t.to_account_id) and a.name ilike :q escape '\\'))
            """;

    StatementSpec bind(StatementSpec spec) {
        checked();
        return spec.param("from", from).param("to", to)
                .param("accountId", accountId).param("categoryId", categoryId)
                .param("kind", kind).param("minAmount", minAmount).param("maxAmount", maxAmount).param("tag", tag).param("goalId", goalId).param("person", person)
                .param("q", q == null ? null : "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
                .param("qAmount", q == null ? null : amountIn(q));
    }

    /** "450", "1,250.50" or "₹2,000" as paise; null when the search isn't a number. */
    static Long amountIn(String q) {
        String digits = q.replace(",", "").replace("₹", "").replace("Rs.", "").replace("Rs", "").strip();
        if (!digits.matches("\\d{1,13}(\\.\\d{1,2})?")) return null;
        return new BigDecimal(digits).movePointRight(2).longValueExact();
    }
}
