package dev.payledger.core.ai;

import dev.payledger.core.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Reads money out of free text with a language model: what someone typed or said ("450 petrol yesterday on the HDFC
 * card"), or a bank message the rules couldn't read with confidence. The text is masked first ({@link Masker}); the
 * answer is a fixed JSON shape with the model's confidence, which we then check against the text ourselves (the amount
 * must be in it, the date must be sensible) before anything is trusted. Every call is logged ({@link AiAudit}).
 */
@Service
public class AiReader {

    private static final Logger log = LoggerFactory.getLogger(AiReader.class);
    /** At or above this, an entry is added without asking (the owner's rule: 95%). */
    public static final double SURE = 0.95;
    private static final int MAX_INPUT = 4_000;
    private static final int PER_MINUTE = 20;

    private final LlmClient llm;
    private final AiAudit audit;
    private final AiConfig.Prices prices;
    private final JsonMapper json;

    AiReader(LlmClient llm, AiAudit audit, AiConfig.Prices prices, JsonMapper json) {
        this.llm = llm;
        this.audit = audit;
        this.prices = prices;
        this.json = json;
    }

    public boolean enabled() {
        return !"none".equals(llm.provider());
    }

    /**
     * What the model may choose from (names only: no numbers or balances), and how to read dates and currency.
     *
     * @param bankMessage true for an SMS or email from a bank, false for something the person typed or said
     */
    public record Context(List<String> accounts, List<String> expenseCategories, List<String> incomeCategories, List<String> people,
                          String currency, LocalDate today, boolean bankMessage) {
    }

    /**
     * One entry as read. {@code confidence} is ours (the model's, lowered by our checks); {@code missing} names what
     * the text didn't say (the account, say), which keeps it from being added without asking.
     */
    public record Entry(String kind, long amount, String currency, LocalDate date, boolean dateGiven, String description, String merchant,
                        String account, String toAccount, String category, String counterparty, double modelConfidence,
                        double confidence, List<String> missing, List<String> checks) {
    }

    /** @param transaction false when the text isn't a transaction at all (an OTP, an offer, a decline) */
    public record Reading(UUID requestId, String cleanText, boolean transaction, String reason, List<Entry> entries) {
    }

    /**
     * @param counted  uses one of the month's AI reads (the caller checked the allowance)
     * @param original the unmasked text, for checking the answer (never sent anywhere)
     */
    public Reading read(UUID userId, String ownName, String feature, String channel, String original, Context ctx, boolean counted) {
        if (!enabled()) {
            throw new AiOffException();
        }
        if (audit.lastMinute(userId) >= PER_MINUTE) {
            throw new AiBusyException();
        }
        String text = original.length() > MAX_INPUT ? original.substring(0, MAX_INPUT) : original;
        Masker.Masked masked = Masker.mask(text, ownName);
        String user = userMessage(masked.text(), ctx);
        long started = System.nanoTime();
        LlmClient.Completion done;
        try {
            done = llm.complete(SYSTEM, user, 2_000);
        } catch (LlmClient.LlmException e) {
            long ms = (System.nanoTime() - started) / 1_000_000;
            log.warn("AI read failed: {}", e.getMessage());
            audit.record(new AiAudit.Call(userId, feature, channel, llm.provider(), llm.model(), "ERROR", e.getMessage(), 0, 0, null, 0, ms,
                    user, null, false, null, null, "FAILED", 0, null));
            throw new AiUnavailableException();
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        long cost = prices.costMicros(done.inputTokens(), done.outputTokens());
        Reading reading;
        try {
            reading = parse(done.text(), text, ctx);
        } catch (RuntimeException e) {
            audit.record(new AiAudit.Call(userId, feature, channel, llm.provider(), done.model(), "INVALID", "unreadable answer: " + e.getMessage(),
                    done.inputTokens(), done.outputTokens(), null, cost, ms, user, done.text(), counted, null, null, "FAILED", 0, null));
            throw new AiUnavailableException();
        }
        double best = reading.entries().stream().mapToDouble(Entry::confidence).max().orElse(0);
        double model = reading.entries().stream().mapToDouble(Entry::modelConfidence).max().orElse(0);
        UUID id = audit.record(new AiAudit.Call(userId, feature, channel, llm.provider(), done.model(), "OK", null, done.inputTokens(),
                done.outputTokens(), null, cost, ms, user, done.text(), counted, reading.entries().isEmpty() ? null : model,
                reading.entries().isEmpty() ? null : best, reading.transaction() ? null : "NOT_A_TRANSACTION", reading.entries().size(), null));
        return new Reading(id, reading.cleanText(), reading.transaction(), reading.reason(), reading.entries());
    }

    /** A model's answer to someone else's prompt (the assistant), logged; the caller reads it and records the outcome. */
    public record Raw(UUID requestId, String text) {
    }

    /**
     * One call with the caller's own prompt. The user message must already be masked. Same limits and log as {@link #read}.
     */
    public Raw ask(UUID userId, String feature, String channel, String system, String maskedUser, boolean counted, int maxTokens) {
        if (!enabled()) {
            throw new AiOffException();
        }
        if (audit.lastMinute(userId) >= PER_MINUTE) {
            throw new AiBusyException();
        }
        long started = System.nanoTime();
        LlmClient.Completion done;
        try {
            done = llm.complete(system, maskedUser, maxTokens);
        } catch (LlmClient.LlmException e) {
            log.warn("AI call failed: {}", e.getMessage());
            audit.record(new AiAudit.Call(userId, feature, channel, llm.provider(), llm.model(), "ERROR", e.getMessage(), 0, 0, null, 0,
                    (System.nanoTime() - started) / 1_000_000, maskedUser, null, false, null, null, "FAILED", 0, null));
            throw new AiUnavailableException();
        }
        UUID id = audit.record(new AiAudit.Call(userId, feature, channel, llm.provider(), done.model(), "OK", null, done.inputTokens(),
                done.outputTokens(), null, prices.costMicros(done.inputTokens(), done.outputTokens()), (System.nanoTime() - started) / 1_000_000,
                maskedUser, done.text(), counted, null, null, null, null, null));
        return new Raw(id, done.text());
    }

    // ---------------------------------------------------------------- the prompt

    static final String SYSTEM = """
            You read personal-finance entries out of short texts for a budgeting app. Reply with ONE JSON object and nothing else.

            The text is either a bank alert (SMS or email) or something a person typed or said aloud. Private details were
            replaced before you see it: [ACCOUNT], [CARD], [UPI ID], [PHONE], [EMAIL], [NUMBER]; [YOU] is the person themself.

            Shape:
            {"cleanText": string, "isTransaction": boolean, "reason": string,
             "entries": [{"kind": "EXPENSE"|"INCOME"|"TRANSFER", "amount": "1234.50", "currency": "INR", "date": "YYYY-MM-DD",
                          "dateGiven": boolean, "description": string, "merchant": string|null, "account": string|null,
                          "toAccount": string|null, "category": string|null, "counterparty": string|null,
                          "confidence": number, "missing": [string]}]}

            Rules:
            - cleanText: the input with spelling, grammar and spoken numbers fixed ("four fifty" -> "450"). Add no facts.
            - isTransaction false (and no entries) for OTPs, offers, reminders, bills due, declined or failed payments,
              statements' summaries, or anything that isn't money that already moved. Say why in reason.
            - One entry per payment. A person may say several ("200 auto and 450 lunch").
            - amount: the number as written, in major units, digits and one dot only. Never invent or round an amount.
            - kind: EXPENSE money out, INCOME money in, TRANSFER between the person's own accounts (paying their own card
              bill, moving money to [YOU] in another bank). Paying or lending to someone else is an EXPENSE.
            - date: resolve "today", "yesterday", "last Friday" from TODAY. dateGiven false when the text names no date (use TODAY).
            - account / toAccount / category: copy one of the given names exactly, or null. Never make one up.
            - description: 2-5 plain words, like "Petrol", "Lunch at Truffles", "Salary". merchant: the shop or payee if named.
            - counterparty: the person paid or who paid, if a person is named (not [YOU]).
            - currency: the currency written or said, else DEFAULT CURRENCY.
            - confidence (0 to 1), per entry: how sure every field is right. Above 0.95 only when amount, direction and date are
              explicit and nothing is guessed. A guessed account or category lowers it.
            - missing: fields the text didn't give: any of "account", "date", "category".
            """;

    private static String userMessage(String masked, Context ctx) {
        return """
                TODAY: %s
                DEFAULT CURRENCY: %s
                SOURCE: %s
                ACCOUNTS: %s
                EXPENSE CATEGORIES: %s
                INCOME CATEGORIES: %s
                PEOPLE: %s

                TEXT:
                %s
                """.formatted(ctx.today(), ctx.currency(), ctx.bankMessage() ? "bank message" : "typed or spoken by the person",
                list(ctx.accounts()), list(ctx.expenseCategories()), list(ctx.incomeCategories()), list(ctx.people()), masked);
    }

    private static String list(List<String> names) {
        return names == null || names.isEmpty() ? "(none)" : String.join(" | ", names);
    }

    // ---------------------------------------------------------------- reading and checking the answer

    Reading parse(String answer, String original, Context ctx) {
        String body = answer.strip();
        int from = body.indexOf('{');
        int to = body.lastIndexOf('}');
        if (from < 0 || to <= from) {
            throw new IllegalArgumentException("no JSON object");
        }
        JsonNode root = json.readTree(body.substring(from, to + 1));
        boolean transaction = root.path("isTransaction").asBoolean(true);
        List<Entry> entries = new ArrayList<>();
        if (transaction) {
            for (JsonNode e : root.path("entries")) {
                Entry entry = entry(e, original, ctx);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        }
        return new Reading(null, text(root, "cleanText"), transaction && !entries.isEmpty(), text(root, "reason"), entries);
    }

    /** One entry from a model's JSON, checked against the original text (also used by the assistant). */
    Entry entry(JsonNode e, String original, Context ctx) {
        String kind = text(e, "kind");
        if (kind == null || !List.of("EXPENSE", "INCOME", "TRANSFER").contains(kind.toUpperCase(Locale.ROOT))) {
            return null;
        }
        kind = kind.toUpperCase(Locale.ROOT);
        Long amount = minor(text(e, "amount"));
        if (amount == null || amount <= 0) {
            return null;
        }
        double modelConfidence = Math.max(0, Math.min(1, e.path("confidence").asDouble(0)));
        double confidence = modelConfidence;
        List<String> checks = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (JsonNode m : e.path("missing")) {
            missing.add(m.asString(""));
        }
        // The amount must be in the text: a model can misread, but it must never invent money.
        if (!amountInText(amount, original)) {
            confidence = Math.min(confidence, 0.5);
            checks.add("amount-not-in-text");
        }
        LocalDate date = ctx.today();
        boolean dateGiven = e.path("dateGiven").asBoolean(false);
        try {
            date = LocalDate.parse(text(e, "date"));
        } catch (RuntimeException ex) {
            confidence = Math.min(confidence, 0.8);
            checks.add("date-unreadable");
        }
        if (date.isAfter(ctx.today().plusDays(1)) || date.isBefore(ctx.today().minusDays(400))) {
            date = ctx.today();
            confidence = Math.min(confidence, 0.7);
            checks.add("date-out-of-range");
        }
        String account = known(text(e, "account"), ctx.accounts());
        String toAccount = known(text(e, "toAccount"), ctx.accounts());
        String category = known(text(e, "category"), "INCOME".equals(kind) ? ctx.incomeCategories() : ctx.expenseCategories());
        if (text(e, "account") != null && account == null) {
            checks.add("account-not-known");
        }
        String currency = text(e, "currency");
        currency = currency == null || !currency.matches("[A-Za-z]{3}") ? ctx.currency() : currency.toUpperCase(Locale.ROOT);
        if (!currency.equals(ctx.currency())) {
            // Another currency is read correctly, but adding it needs a person to say which account and rate.
            confidence = Math.min(confidence, 0.9);
            checks.add("other-currency");
        }
        String description = text(e, "description");
        if (description == null || description.isBlank()) {
            description = text(e, "merchant") != null ? text(e, "merchant") : "TRANSFER".equals(kind) ? "Transfer" : "Entry";
        }
        return new Entry(kind, amount, currency, date, dateGiven, cut(description, 120), cut(text(e, "merchant"), 120), account, toAccount,
                category, cut(text(e, "counterparty"), 80), modelConfidence, round(confidence), List.copyOf(missing), List.copyOf(checks));
    }

    /** "1,234.50" or "1234.5" in the major unit, as minor units; null when it isn't a plain number. */
    static Long minor(String amount) {
        if (amount == null) {
            return null;
        }
        String s = amount.replace(",", "").replace(" ", "").strip();
        if (!s.matches("\\d{1,13}(\\.\\d{1,2})?")) {
            return null;
        }
        return new BigDecimal(s).movePointRight(2).longValueExact();
    }

    /** The amount, as written anywhere in the text: "10000", "10,000", "1,00,00", "10000.00", "10k", "10 thousand". */
    static boolean amountInText(long minor, String text) {
        String digits = text.replaceAll("(?<=\\d)[,\\s](?=\\d)", "");
        long whole = minor / 100;
        long paise = minor % 100;
        String plain = paise == 0 ? String.valueOf(whole) : whole + "." + String.format("%02d", paise);
        // Not inside a longer number ("Rs.10000" is fine: a dot after letters isn't a decimal point).
        String notAfterNumber = "(?<!\\d)(?<!\\d\\.)";
        if (digits.matches("(?s).*" + notAfterNumber + java.util.regex.Pattern.quote(plain) + "(?!\\d).*")) {
            return true;
        }
        if (paise == 0 && digits.matches("(?s).*" + notAfterNumber + whole + "\\.0{1,2}(?!\\d).*")) {
            return true;
        }
        // Spoken and typed shorthand: 10k, 1.5L, 2 lakh, 3 crore, 5 thousand, 4 fifty (cleaned by the model, so trusted less).
        String lower = text.toLowerCase(Locale.ROOT);
        for (var m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(k|thousand|l|lakh|lakhs|lac|cr|crore|crores)\\b").matcher(lower); m.find(); ) {
            double n = Double.parseDouble(m.group(1));
            double unit = switch (m.group(2)) {
                case "k", "thousand" -> 1_000;
                case "l", "lakh", "lakhs", "lac" -> 100_000;
                default -> 10_000_000;
            };
            if (Math.round(n * unit * 100) == minor) {
                return true;
            }
        }
        return false;
    }

    private static String known(String name, List<String> names) {
        if (name == null || names == null) {
            return null;
        }
        for (String n : names) {
            if (n.equalsIgnoreCase(name.strip())) {
                return n;
            }
        }
        return null;
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asString("").strip();
        return s.isEmpty() || s.equalsIgnoreCase("null") ? null : s;
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static double round(double d) {
        return Math.round(d * 1000) / 1000.0;
    }

    static class AiOffException extends ApiException {
        AiOffException() {
            super(HttpStatus.SERVICE_UNAVAILABLE, "ai-off", "AI reading isn't switched on here");
        }
    }

    static class AiBusyException extends ApiException {
        AiBusyException() {
            super(HttpStatus.TOO_MANY_REQUESTS, "ai-busy", "too many AI requests in a minute; try again shortly");
        }
    }

    static class AiUnavailableException extends ApiException {
        AiUnavailableException() {
            super(HttpStatus.BAD_GATEWAY, "ai-unavailable", "the AI couldn't read it just now; add it by hand or try again");
        }
    }
}
