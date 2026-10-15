package dev.payledger.core.money;

import dev.payledger.core.common.InvalidRequestException;
import dev.payledger.core.common.NotFoundException;
import dev.payledger.core.common.Uuid7;
import dev.payledger.core.space.SpaceAccess;
import dev.payledger.core.user.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Smart categories (docs/plan/ideas-2026-10.md, idea 3): a category guessed from the description, so the same merchant
 * never needs choosing twice. Rules come first ("always put Swiggy under Eating out"); otherwise what you chose most
 * often for that description in the last year; otherwise for descriptions starting with the same word.
 */
@Service
public class CategoryRuleService {

    static final int APPLY_LIMIT = 500;

    private final JdbcClient jdbc;
    private final SpaceAccess access;
    private final CategoryService categories;
    private final TransactionService transactions;

    public CategoryRuleService(JdbcClient jdbc, SpaceAccess access, CategoryService categories, TransactionService transactions) {
        this.jdbc = jdbc;
        this.access = access;
        this.categories = categories;
        this.transactions = transactions;
    }

    public record Rule(UUID id, String match, UUID categoryId, int used, Instant createdAt) {
    }

    /**
     * @param source RULE (one of yours, ruleId set), HISTORY (what you chose before for this description) or
     *               SIMILAR (descriptions starting with the same word)
     * @param times  how many past transactions back a HISTORY or SIMILAR guess
     */
    public record Guess(UUID categoryId, String source, UUID ruleId, String match, int times) {
    }

    /** @param applied how many past transactions were moved to the rule's category */
    public record Applied(int applied) {
    }

    @Transactional(readOnly = true)
    public List<Rule> list(CurrentUser user, UUID spaceId) {
        access.read(user, spaceId);
        return jdbc.sql("select id, match, category_id, used, created_at from category_rules where space_id = :spaceId order by match")
                .param("spaceId", spaceId).query(Rule.class).list();
    }

    /** Adds a rule, or points an existing one for the same words at this category. */
    @Transactional
    public Rule save(CurrentUser user, UUID spaceId, String match, UUID categoryId) {
        access.write(user, spaceId);
        String m = normalise(match);
        Category category = categories.find(spaceId, categoryId);
        if (category.hidden()) {
            throw new InvalidRequestException("category-hidden", "category " + category.name() + " is hidden");
        }
        jdbc.sql("""
                        insert into category_rules (id, space_id, match, category_id, created_by)
                        values (:id, :spaceId, :match, :categoryId, :userId)
                        on conflict (space_id, match) do update set category_id = excluded.category_id
                        """)
                .param("id", Uuid7.next()).param("spaceId", spaceId).param("match", m).param("categoryId", categoryId)
                .param("userId", user.id()).update();
        return jdbc.sql("select id, match, category_id, used, created_at from category_rules where space_id = :spaceId and match = :match")
                .param("spaceId", spaceId).param("match", m).query(Rule.class).single();
    }

    @Transactional
    public void delete(CurrentUser user, UUID spaceId, UUID id) {
        access.write(user, spaceId);
        if (jdbc.sql("delete from category_rules where id = :id and space_id = :spaceId").param("id", id).param("spaceId", spaceId).update() == 0) {
            throw new NotFoundException("rule");
        }
    }

    /**
     * Moves past transactions whose description contains the rule's words into its category. Each move is an ordinary
     * edit, so the ledger reverses and reposts it. At most {@value APPLY_LIMIT} at a time.
     */
    @Transactional
    public Applied apply(CurrentUser user, UUID spaceId, UUID id) {
        access.write(user, spaceId);
        Rule rule = jdbc.sql("select id, match, category_id, used, created_at from category_rules where id = :id and space_id = :spaceId")
                .param("id", id).param("spaceId", spaceId).query(Rule.class).optional().orElseThrow(() -> new NotFoundException("rule"));
        String kind = categories.find(spaceId, rule.categoryId()).kind();
        List<UUID> ids = jdbc.sql("""
                        select id from transactions
                        where space_id = :spaceId and deleted_at is null and kind = :kind
                          and position(:match in lower(description)) > 0
                          and category_id is distinct from :categoryId
                        order by occurred_on desc limit :limit
                        """)
                .param("spaceId", spaceId).param("kind", kind).param("match", rule.match()).param("categoryId", rule.categoryId())
                .param("limit", APPLY_LIMIT).query(UUID.class).list();
        for (UUID txnId : ids) {
            Transaction t = transactions.get(user, spaceId, txnId);
            transactions.replace(user, spaceId, txnId, new TransactionRequests.Replace(t.version(), t.kind(), t.amount(), t.accountId(),
                    t.toAccountId(), rule.categoryId(), t.occurredOn(), t.description(), t.note(), t.tags(), t.goalId(), t.counterparty()));
        }
        return new Applied(ids.size());
    }

    @Transactional(readOnly = true)
    public Guess guess(CurrentUser user, UUID spaceId, String kind, String description) {
        access.read(user, spaceId);
        return guess(spaceId, kind, description, false);
    }

    /**
     * The best guess, or null. Hidden categories are never guessed.
     *
     * @param countUse true when the guess is being used (an import), so a rule's count goes up
     */
    Guess guess(UUID spaceId, String kind, String description, boolean countUse) {
        if (description == null || description.isBlank() || !("EXPENSE".equals(kind) || "INCOME".equals(kind))) {
            return null;
        }
        String d = description.strip().toLowerCase(Locale.ROOT);
        // The longest matching rule is the most specific ("swiggy instamart" over "swiggy").
        Guess rule = jdbc.sql("""
                        select r.category_id, 'RULE' as source, r.id as rule_id, r.match, 0 as times
                        from category_rules r join categories c on c.id = r.category_id
                        where r.space_id = :spaceId and c.kind = :kind and not c.hidden and position(r.match in :d) > 0
                        order by length(r.match) desc limit 1
                        """)
                .param("spaceId", spaceId).param("kind", kind).param("d", d).query(Guess.class).optional().orElse(null);
        if (rule != null) {
            if (countUse) {
                jdbc.sql("update category_rules set used = used + 1 where id = :id").param("id", rule.ruleId()).update();
            }
            return rule;
        }
        Guess history = mostUsed(spaceId, kind, "lower(trim(t.description)) = :d", d, "HISTORY");
        if (history != null) {
            return history;
        }
        String first = d.split("[\\s*/#:-]+")[0];
        return first.length() >= 3 ? mostUsed(spaceId, kind, "lower(trim(t.description)) like :d || '%'", first, "SIMILAR") : null;
    }

    private Guess mostUsed(UUID spaceId, String kind, String condition, String d, String source) {
        return jdbc.sql("""
                        select t.category_id, :source as source, null::uuid as rule_id, null as match, count(*)::int as times
                        from transactions t join categories c on c.id = t.category_id
                        where t.space_id = :spaceId and t.deleted_at is null and t.kind = :kind and not c.hidden
                          and t.occurred_on > current_date - 365 and\s""" + condition + """

                        group by t.category_id order by count(*) desc, max(t.occurred_on) desc limit 1
                        """)
                .param("spaceId", spaceId).param("kind", kind).param("d", d).param("source", source)
                .query(Guess.class).optional().orElse(null);
    }

    private static String normalise(String match) {
        String m = match == null ? "" : match.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (m.length() < 2 || m.length() > 60) {
            throw new InvalidRequestException("invalid-rule", "the words to match are 2 to 60 characters");
        }
        return m;
    }
}
