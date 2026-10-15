package dev.payledger.core.ai;

import dev.payledger.core.common.ApiException;
import dev.payledger.core.common.Uuid7;
import dev.payledger.core.money.CategoryRuleService;
import dev.payledger.core.money.Transaction;
import dev.payledger.core.money.TransactionRequests;
import dev.payledger.core.money.TransactionService;
import dev.payledger.core.plan.PlanService;
import dev.payledger.core.settings.AppSettings;
import dev.payledger.core.user.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quick add: what someone typed or said ("450 petrol yesterday on HDFC", "got 50k salary") becomes entries. The AI
 * reads it; an entry it's at least 95% sure of, with a known account and nothing like it already entered, is added
 * straight away (and can be undone); anything less comes back as a draft to check. Without AI (switched off, or the
 * month's Free allowance used) a simple reading of the amount and words is offered as a draft instead.
 */
@Service
public class QuickAddService {

    private static final int MAX_TEXT = 1_000;

    private final AiReader reader;
    private final AiContexts contexts;
    private final AiAudit audit;
    private final PlanService plans;
    private final AppSettings settings;
    private final TransactionService transactions;
    private final CategoryRuleService rules;
    private final JdbcClient jdbc;

    QuickAddService(AiReader reader, AiContexts contexts, AiAudit audit, PlanService plans, AppSettings settings,
                    TransactionService transactions, CategoryRuleService rules, JdbcClient jdbc) {
        this.reader = reader;
        this.contexts = contexts;
        this.audit = audit;
        this.plans = plans;
        this.settings = settings;
        this.transactions = transactions;
        this.rules = rules;
        this.jdbc = jdbc;
    }

    /**
     * @param status     ADDED (already a transaction: {@code transactionId}, {@code version} for undo) or DRAFT (check and add)
     * @param missing    what the text didn't say (account, date, category)
     * @param why        why a draft wasn't added on its own, in plain words
     */
    public record Entry(String status, String kind, long amount, String currency, LocalDate occurredOn, String description, String counterparty,
                        UUID accountId, UUID toAccountId, UUID categoryId, double confidence, List<String> missing, List<String> why,
                        UUID transactionId, Integer version, UUID duplicateOf) {
    }

    /** @param limit this month's AI reads on Free (null on Premium), {@code used} so far */
    public record Usage(Integer limit, long used) {
    }

    /** @param ai the AI read it (false: the simple reading); {@code notice} why not, when it didn't */
    public record Result(String cleanText, boolean ai, String notice, List<Entry> entries, Usage usage) {
    }

    public record Status(boolean enabled, boolean speechToText, Usage usage) {
    }

    public Usage usage(CurrentUser user) {
        PlanService.Status s = plans.status(user);
        Integer limit = s.limits() == null ? null : s.limits().get(PlanService.Limit.AI.key());
        return new Usage(limit, s.usage().getOrDefault(PlanService.Limit.AI.key(), 0L));
    }

    /** @param channel TYPED or VOICE */
    public Result add(CurrentUser user, String text, String channel) {
        if (text == null || text.isBlank()) {
            throw new NothingToAdd();
        }
        String said = text.strip();
        if (said.length() > MAX_TEXT) {
            said = said.substring(0, MAX_TEXT);
        }
        UUID space = user.personalSpaceId();
        AiContexts.Space ctx = contexts.of(user.id(), space, false);
        String notice = null;
        AiReader.Reading reading = null;
        if (reader.enabled()) {
            try {
                plans.check(space, PlanService.Limit.AI);
                reading = reader.read(user.id(), user.displayName(), "QUICK_ADD", channel, said, ctx.context(), true);
            } catch (ApiException e) {
                notice = switch (e.code()) {
                    case "plan-limit" -> "You've used this month's " + settings.freeLimit(PlanService.Limit.AI.key())
                            + " AI reads on Free, so this is a simple reading. Check it before adding, or go Premium for unlimited.";
                    case "ai-busy" -> "That's a lot at once. Here's a simple reading; check it before adding.";
                    default -> "The AI couldn't read it just now. Here's a simple reading; check it before adding.";
                };
            }
        }
        if (reading == null) {
            return new Result(said, false, notice, simple(user, said, ctx), usage(user));
        }
        if (!reading.transaction()) {
            audit.outcome(reading.requestId(), "NOT_A_TRANSACTION", null, null, null);
            return new Result(reading.cleanText(), true, reading.reason() == null || reading.reason().isBlank()
                    ? "That doesn't sound like money spent or received. Try \"450 petrol yesterday\"."
                    : "That doesn't sound like money spent or received (" + reading.reason() + ").", List.of(), usage(user));
        }
        List<Entry> out = new ArrayList<>();
        List<UUID> added = new ArrayList<>();
        double best = 0;
        for (AiReader.Entry e : reading.entries()) {
            Entry entry = decide(user, ctx, e);
            if ("ADDED".equals(entry.status())) {
                added.add(entry.transactionId());
            }
            best = Math.max(best, entry.confidence());
            out.add(entry);
        }
        audit.outcome(reading.requestId(), added.isEmpty() ? "TO_REVIEW" : added.size() == out.size() ? "AUTO_ADDED" : "PARTLY_ADDED", best, added, null);
        return new Result(reading.cleanText(), true, null, out, usage(user));
    }

    /** Resolves names to this space's ids, then adds it if every check passes; otherwise a draft says why not. */
    private Entry decide(CurrentUser user, AiContexts.Space ctx, AiReader.Entry e) {
        UUID space = user.personalSpaceId();
        List<String> why = new ArrayList<>();
        double confidence = e.confidence();
        UUID account = ctx.account(e.account());
        UUID toAccount = ctx.account(e.toAccount());
        List<String> missing = new ArrayList<>(e.missing());
        if (account == null) {
            // Not said: the first account is offered, but that's a guess, so it's never added on its own.
            account = ctx.accounts().values().stream().findFirst().orElse(null);
            if (!missing.contains("account")) {
                missing.add("account");
            }
            why.add("Which account? You didn't say.");
        }
        if ("TRANSFER".equals(e.kind()) && (toAccount == null || toAccount.equals(account))) {
            why.add("Which account did it go to?");
        }
        UUID category = "TRANSFER".equals(e.kind()) ? null : ctx.category(e.kind(), e.category());
        if (category == null && !"TRANSFER".equals(e.kind())) {
            CategoryRuleService.Guess g = rules.guess(user, space, e.kind(), e.merchant() != null ? e.merchant() : e.description());
            category = g != null ? g.categoryId() : ctx.category(e.kind(), "Other");
        }
        String accountCurrency = account == null ? ctx.currency() : ctx.accountCurrency().getOrDefault(account, ctx.currency());
        if (!e.currency().equals(accountCurrency)) {
            why.add("It's in " + e.currency() + ": check the account and amount.");
        }
        if (!e.checks().isEmpty() && e.checks().contains("amount-not-in-text")) {
            why.add("Check the amount: it wasn't written like that.");
        }
        UUID twin = account == null ? null : jdbc.sql("""
                        select id from transactions where space_id = :s and deleted_at is null and kind = :k and amount = :a
                          and occurred_on between cast(:on as date) - 2 and cast(:on as date) + 2
                        order by abs(occurred_on - cast(:on as date)) limit 1
                        """).param("s", space).param("k", e.kind()).param("a", e.amount()).param("on", e.date())
                .query(UUID.class).optional().orElse(null);
        if (twin != null) {
            why.add("Looks like one you already have.");
        }
        if (confidence < AiReader.SURE && why.isEmpty()) {
            why.add("Not sure enough to add it on its own: check it.");
        }
        if (why.isEmpty() && account != null) {
            Transaction t = transactions.create(user, space, new TransactionRequests.Create(Uuid7.next(), e.kind(), e.amount(), account,
                    "TRANSFER".equals(e.kind()) ? toAccount : null, category, e.date(), e.description(), null, List.of("ai"), null,
                    e.counterparty())).transaction();
            return new Entry("ADDED", e.kind(), e.amount(), e.currency(), e.date(), e.description(), e.counterparty(), account,
                    t.toAccountId(), category, confidence, List.copyOf(missing), List.of(), t.id(), t.version(), null);
        }
        return new Entry("DRAFT", e.kind(), e.amount(), e.currency(), e.date(), e.description(), e.counterparty(), account,
                "TRANSFER".equals(e.kind()) ? toAccount : null, category, Math.min(confidence, AiReader.SURE - 0.001), List.copyOf(missing),
                List.copyOf(why), null, null, twin);
    }

    // ---------------------------------------------------------------- without AI

    private static final Pattern AMOUNT = Pattern.compile("(?i)(?:rs\\.?|inr|₹)?\\s*(\\d[\\d,]*(?:\\.\\d{1,2})?)\\s*(k|l|lakh|lakhs|cr|crore)?\\b");
    private static final Pattern INCOME_WORDS = Pattern.compile("(?i)\\b(got|received|salary|income|credited|refund|cashback|earned|bonus|interest)\\b");

    /** "250 swiggy" → a draft expense of 250 called Swiggy; "got 50k salary" → income. Never added without a look. */
    private List<Entry> simple(CurrentUser user, String said, AiContexts.Space ctx) {
        Matcher m = AMOUNT.matcher(said);
        Long amount = null;
        while (m.find() && amount == null) {
            String unit = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
            Long base = AiReader.minor(m.group(1));
            if (base != null && base > 0) {
                long mult = switch (unit) {
                    case "k" -> 1_000;
                    case "l", "lakh", "lakhs" -> 100_000;
                    case "cr", "crore" -> 10_000_000;
                    default -> 1;
                };
                amount = base * mult;
            }
        }
        if (amount == null) {
            return List.of();
        }
        String kind = INCOME_WORDS.matcher(said).find() ? "INCOME" : "EXPENSE";
        String words = said.replaceAll("(?i)(?:rs\\.?|inr|₹)?\\s*\\d[\\d,]*(?:\\.\\d{1,2})?\\s*(k|l|lakh|lakhs|cr|crore)?\\b", " ")
                .replaceAll("(?i)\\b(spent|paid|for|on|got|received|rupees|rs|today)\\b", " ").replaceAll("\\s+", " ").strip();
        String description = words.isEmpty() ? ("INCOME".equals(kind) ? "Income" : "Expense")
                : Character.toUpperCase(words.charAt(0)) + words.substring(1);
        description = description.length() > 120 ? description.substring(0, 120) : description;
        UUID account = ctx.accounts().values().stream().findFirst().orElse(null);
        CategoryRuleService.Guess g = rules.guess(user, user.personalSpaceId(), kind, description);
        UUID category = g != null ? g.categoryId() : ctx.category(kind, "Other");
        return List.of(new Entry("DRAFT", kind, amount, ctx.currency(), ctx.context().today(), description, null, account, null, category,
                0.5, List.of("account", "date"), List.of("A simple reading: check it."), null, null, null));
    }

    static class NothingToAdd extends ApiException {
        NothingToAdd() {
            super(HttpStatus.BAD_REQUEST, "nothing-to-add", "say or type what you spent or received");
        }
    }
}
