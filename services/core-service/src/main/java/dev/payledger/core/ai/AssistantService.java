package dev.payledger.core.ai;

import dev.payledger.core.budget.BudgetService;
import dev.payledger.core.common.ApiException;
import dev.payledger.core.money.CategoryRuleService;
import dev.payledger.core.notify.Reminders;
import dev.payledger.core.plan.PlanService;
import dev.payledger.core.settings.AppSettings;
import dev.payledger.core.user.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Voice mode: one model call turns what was said (one thing or ten) into proposed actions: entries, budgets, bills,
 * paying a bill, goals, questions about spending, going to a page, and changes to what's already proposed. Nothing is
 * done here: each proposal comes back checked and resolved to this space's ids, with the request the app will send
 * through its normal API once the person says "add it". Questions are answered here, from the data, not by the model.
 */
@Service
public class AssistantService {

    private static final int MAX_TURNS = 8;
    private static final int MAX_TEXT = 2_000;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final AiReader reader;
    private final AiContexts contexts;
    private final AiAudit audit;
    private final PlanService plans;
    private final AppSettings settings;
    private final BudgetService budgets;
    private final CategoryRuleService rules;
    private final QuickAddService quickAdd;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    AssistantService(AiReader reader, AiContexts contexts, AiAudit audit, PlanService plans, AppSettings settings, BudgetService budgets,
                     CategoryRuleService rules, QuickAddService quickAdd, JdbcClient jdbc, JsonMapper json) {
        this.reader = reader;
        this.contexts = contexts;
        this.audit = audit;
        this.plans = plans;
        this.settings = settings;
        this.budgets = budgets;
        this.rules = rules;
        this.quickAdd = quickAdd;
        this.jdbc = jdbc;
        this.json = json;
    }

    /** A line of the conversation so far (newest last). */
    public record Turn(String role, String text) {
    }

    /** What's on screen waiting for a yes: its position and a one-line summary, so "make the second one 500" works. */
    public record Shown(int index, String type, String summary) {
    }

    /**
     * A proposed action, ready to run through the app's own API ({@code body}) when confirmed.
     *
     * @param type     TRANSACTION, BUDGET, BILL, PAY_BILL, GOAL
     * @param ready    every required field is known; if not, {@code why} says what's missing and it can't run until fixed
     * @param replaces the position of a shown proposal this one corrects ("make it 500"), else null
     * @param page     where it lives in the app, to show it there
     */
    public record Proposal(String id, String type, String title, String summary, Long amount, String currency, double confidence,
                           boolean ready, List<String> why, Map<String, Object> body, Integer replaces, String page) {
    }

    /**
     * @param say      a short reply to show and speak
     * @param heard    what was said, cleaned up
     * @param intent   what the model made of a plain reply: CONFIRM, CANCEL, UNDO, STOP, or null
     * @param navigate a page to go to now, or null
     */
    public record Reply(UUID requestId, String say, String heard, String intent, List<Proposal> proposals, String navigate,
                        QuickAddService.Usage usage) {
    }

    public Reply talk(CurrentUser user, List<Turn> turns, List<Shown> shown, String page, String channel) {
        if (turns == null || turns.isEmpty() || turns.getLast().text() == null || turns.getLast().text().isBlank()) {
            throw new NothingSaid();
        }
        UUID space = user.personalSpaceId();
        plans.check(space, PlanService.Limit.AI);
        AiContexts.Space ctx = contexts.of(user.id(), space, false);
        List<Turn> recent = turns.subList(Math.max(0, turns.size() - MAX_TURNS), turns.size());
        String said = recent.getLast().text().strip();
        if (said.length() > MAX_TEXT) {
            said = said.substring(0, MAX_TEXT);
        }
        Known known = known(space);
        String message = message(user, ctx, known, recent, shown, page, said);
        // Amounts are checked against everything the person said in this conversation: "put it on HDFC" has none.
        StringBuilder everything = new StringBuilder(said);
        for (Turn t : recent) {
            if (!"assistant".equals(t.role()) && t.text() != null) {
                everything.append(" \n").append(t.text());
            }
        }
        if (shown != null) {
            shown.forEach(x -> everything.append(" \n").append(x.summary()));
        }
        AiReader.Raw raw = reader.ask(user.id(), "ASSISTANT", channel, SYSTEM, message, true, 2_500);
        JsonNode root;
        try {
            String t = raw.text().strip();
            root = json.readTree(t.substring(t.indexOf('{'), t.lastIndexOf('}') + 1));
        } catch (RuntimeException e) {
            audit.invalid(raw.requestId(), "unreadable answer: " + e.getMessage());
            throw new AiReader.AiUnavailableException();
        }
        StringBuilder answers = new StringBuilder();
        List<Proposal> proposals = new ArrayList<>();
        String navigate = page(AiReader.text(root, "navigate"));
        for (JsonNode a : root.path("actions")) {
            String type = orEmpty(AiReader.text(a, "type")).toLowerCase(Locale.ROOT);
            Integer replaces = a.path("replaces").isInt() ? a.path("replaces").asInt() : null;
            try {
                switch (type) {
                    case "add_transaction" -> proposals.add(transaction(user, ctx, a, everything.toString(), replaces));
                    case "set_budget" -> proposals.add(budget(user, ctx, a, replaces));
                    case "add_bill" -> proposals.add(bill(user, ctx, a, replaces));
                    case "pay_bill" -> proposals.add(payBill(ctx, known, a, replaces));
                    case "add_goal" -> proposals.add(goal(ctx, a, replaces));
                    case "ask_spending" -> answers.append(' ').append(spending(space, ctx, a));
                    case "ask_budget" -> answers.append(' ').append(budgetStatus(user, ctx, a));
                    default -> {
                    }
                }
            } catch (RuntimeException e) {
                // One action it couldn't make sense of doesn't spoil the rest.
            }
        }
        String intent = AiReader.text(root, "intent");
        intent = intent == null ? null : switch (intent.toUpperCase(Locale.ROOT)) {
            case "CONFIRM", "CANCEL", "UNDO", "STOP" -> intent.toUpperCase(Locale.ROOT);
            default -> null;
        };
        double best = proposals.stream().mapToDouble(Proposal::confidence).max().orElse(0);
        audit.outcome(raw.requestId(), proposals.isEmpty() ? (intent != null ? "INTENT_" + intent : "ANSWERED") : "PROPOSED",
                proposals.isEmpty() ? null : best, null, null);
        if (navigate == null && !proposals.isEmpty() && proposals.stream().map(Proposal::page).distinct().count() == 1) {
            navigate = proposals.getFirst().page();
        }
        // What's ready is said by us, from the proposals: a model may say "done" before anything is.
        String say = proposals.isEmpty() ? orEmpty(AiReader.text(root, "say")) : ready(proposals);
        if (!answers.isEmpty()) {
            say = proposals.isEmpty() && intent == null ? answers.toString().strip() : (say + answers).strip();
        }
        return new Reply(raw.requestId(), say.strip(), AiReader.text(root, "heard"), intent, proposals, navigate, quickAdd.usage(user));
    }

    /** "3 budgets, an entry and a bill ready. Say add them to save." with what still needs an answer. */
    static String ready(List<Proposal> proposals) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Proposal p : proposals) {
            counts.merge(p.type(), 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((type, n) -> parts.add(switch (type) {
            case "TRANSACTION" -> n == 1 ? "an entry" : n + " entries";
            case "BUDGET" -> n == 1 ? "a budget" : n + " budgets";
            case "BILL" -> n == 1 ? "a bill" : n + " bills";
            case "PAY_BILL" -> n == 1 ? "a bill paid" : n + " bills paid";
            case "GOAL" -> n == 1 ? "a goal" : n + " goals";
            default -> n + " more";
        }));
        String list = parts.size() == 1 ? parts.getFirst() : String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.getLast();
        long open = proposals.stream().filter(p -> !p.ready()).count();
        String first = Character.toUpperCase(list.charAt(0)) + list.substring(1) + " ready.";
        if (open > 0) {
            String q = proposals.stream().filter(p -> !p.ready()).findFirst().map(p -> p.why().isEmpty() ? "" : " " + p.title() + ": " + p.why().getFirst()).orElse("");
            return first + q + (open == proposals.size() ? "" : " Say add them to save the rest.");
        }
        return first + (proposals.size() == 1 ? " Say add it to save." : " Say add them to save.");
    }

    // ---------------------------------------------------------------- the prompt

    static final String SYSTEM = """
            You are the voice assistant of a personal-finance app. The person speaks; you turn what they say into actions.
            Reply with ONE JSON object and nothing else:
            {"heard": string, "say": string, "intent": null|"CONFIRM"|"CANCEL"|"UNDO"|"STOP", "navigate": null|PAGE, "actions": [ACTION...]}

            - heard: what they said with spelling, grammar and spoken numbers fixed ("four fifty" -> "450").
            - say: one or two short, friendly sentences to speak back, e.g. "Four entries and two budgets. Say add them to save."
              Never read every field aloud; never claim something is saved (it isn't until they confirm).
            - intent: when the reply is only a yes ("yes", "add it", "save all", "do it", "okay") CONFIRM; a no ("cancel", "no",
              "leave it") CANCEL; "undo that" UNDO; "stop", "bye", "that's all" STOP. Otherwise null.
            - navigate: when they ask to open or see a page. PAGE is one of: dashboard, transactions, budgets, plan, accounts,
              categories, goals, reports, groups, capture, settings, utilities, progress.
            - actions: one per thing they asked for, in order. Many at once is normal ("food budget 8000, rent 20000 and I
              spent 450 on petrol" is three actions).

            ACTION types (amounts as written, major units, digits and one dot only; never invent or round an amount):
            {"type":"add_transaction","kind":"EXPENSE"|"INCOME"|"TRANSFER","amount":"450","currency":"INR","date":"YYYY-MM-DD",
             "dateGiven":bool,"description":"Petrol","merchant":string|null,"account":NAME|null,"toAccount":NAME|null,
             "category":NAME|null,"counterparty":string|null,"confidence":0..1,"missing":["account"|"date"|"category"]}
            {"type":"set_budget","category":NAME,"amount":"8000","month":"YYYY-MM","confidence":0..1}
            {"type":"add_bill","name":"Netflix","amount":"649","everyMonths":1|2|3|4|6|12,"firstDue":"YYYY-MM-DD",
             "account":NAME|null,"category":NAME|null,"confidence":0..1}   (bills, EMIs, rent, subscriptions that repeat)
            {"type":"pay_bill","bill":BILL NAME,"date":"YYYY-MM-DD"|null,"amount":"..."|null,"confidence":0..1}
            {"type":"add_goal","name":"Goa trip","amount":"50000","by":"YYYY-MM-DD"|null,"confidence":0..1}
            {"type":"ask_spending","metric":"spent"|"earned","category":NAME|null,"merchant":string|null,"from":"YYYY-MM-DD","to":"YYYY-MM-DD"}
            {"type":"ask_budget","category":NAME|null}
            To change something already proposed (SHOWN), send ONLY the changed ones, each as the full corrected action with
            "replaces": its index. Never send SHOWN items again unchanged.

            Rules:
            - Names (accounts, categories, bills) must be copied exactly from the lists given, or null. Never make one up.
            - Resolve "today", "yesterday", "last Friday", "this month", "next month" from TODAY.
            - TRANSFER is between their own accounts; paying or lending to a person is an EXPENSE with counterparty.
            - confidence: how sure every field is right; above 0.95 only when amount, direction and date are explicit.
            - If you can't tell what they want, no actions; ask one short question in say.
            Placeholders like [ACCOUNT], [PHONE], [YOU] replace private details.
            """;

    private record Known(List<String> bills, Map<String, UUID> billIds, List<String> goals) {
    }

    private Known known(UUID space) {
        record B(UUID id, String name) {
        }
        Map<String, UUID> bills = new LinkedHashMap<>();
        for (B b : jdbc.sql("select id, name from commitments where space_id = :s and (ends_on is null or ends_on >= current_date) order by name")
                .param("s", space).query(B.class).list()) {
            bills.putIfAbsent(b.name(), b.id());
        }
        List<String> goals = jdbc.sql("select name from goals where space_id = :s and archived_at is null order by name").param("s", space)
                .query(String.class).list();
        return new Known(List.copyOf(bills.keySet()), bills, goals);
    }

    private String message(CurrentUser user, AiContexts.Space ctx, Known known, List<Turn> turns, List<Shown> shown, String page, String said) {
        StringBuilder m = new StringBuilder();
        m.append("TODAY: ").append(ctx.context().today()).append(" (").append(ctx.context().today().getDayOfWeek()).append(")\n");
        m.append("DEFAULT CURRENCY: ").append(ctx.currency()).append('\n');
        m.append("CURRENT PAGE: ").append(page == null ? "dashboard" : page).append('\n');
        m.append("ACCOUNTS: ").append(join(ctx.context().accounts())).append('\n');
        m.append("EXPENSE CATEGORIES: ").append(join(ctx.context().expenseCategories())).append('\n');
        m.append("INCOME CATEGORIES: ").append(join(ctx.context().incomeCategories())).append('\n');
        m.append("BILLS: ").append(join(known.bills())).append('\n');
        m.append("GOALS: ").append(join(known.goals())).append('\n');
        m.append("SHOWN (waiting for a yes):");
        if (shown == null || shown.isEmpty()) {
            m.append(" (none)\n");
        } else {
            m.append('\n');
            for (Shown s : shown) {
                m.append("  [").append(s.index()).append("] ").append(s.type()).append(": ").append(Masker.mask(s.summary(), user.displayName()).text()).append('\n');
            }
        }
        m.append("\nCONVERSATION:\n");
        for (int i = 0; i < turns.size(); i++) {
            Turn t = turns.get(i);
            String text = i == turns.size() - 1 ? said : t.text();
            m.append("assistant".equals(t.role()) ? "ASSISTANT: " : "PERSON: ").append(Masker.mask(text == null ? "" : text, user.displayName()).text()).append('\n');
        }
        return m.toString();
    }

    // ---------------------------------------------------------------- proposals

    private Proposal transaction(CurrentUser user, AiContexts.Space ctx, JsonNode a, String said, Integer replaces) {
        AiReader.Entry e = reader.entry(a, said, ctx.context());
        if (e == null) {
            throw new IllegalArgumentException("not an entry");
        }
        List<String> why = new ArrayList<>();
        UUID account = ctx.account(e.account());
        UUID toAccount = ctx.account(e.toAccount());
        if (account == null) {
            why.add("Which account?");
        }
        if ("TRANSFER".equals(e.kind()) && (toAccount == null || toAccount.equals(account))) {
            why.add("To which account?");
        }
        UUID category = null;
        String categoryName = e.category();
        if (!"TRANSFER".equals(e.kind())) {
            category = ctx.category(e.kind(), e.category());
            if (category == null) {
                CategoryRuleService.Guess g = rules.guess(user, user.personalSpaceId(), e.kind(), e.merchant() != null ? e.merchant() : e.description());
                category = g != null ? g.categoryId() : ctx.category(e.kind(), "Other");
                categoryName = g != null ? null : "Other";
            }
        }
        if (e.checks().contains("amount-not-in-text")) {
            why.add("Check the amount.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", e.kind());
        body.put("amount", e.amount());
        body.put("accountId", account);
        body.put("toAccountId", "TRANSFER".equals(e.kind()) ? toAccount : null);
        body.put("categoryId", category);
        body.put("occurredOn", e.date().toString());
        body.put("description", e.description());
        body.put("counterparty", e.counterparty());
        body.put("tags", List.of("ai", "voice"));
        String where = e.account() != null ? e.account() : "no account";
        String summary = String.join(" · ", nonNull(Reminders.money(e.amount(), e.currency()), where, categoryName, day(e.date(), ctx.context().today())));
        String title = ("INCOME".equals(e.kind()) ? "Received: " : "TRANSFER".equals(e.kind()) ? "Moved: " : "") + e.description();
        return new Proposal(UUID.randomUUID().toString(), "TRANSACTION", title, summary, e.amount(), e.currency(), e.confidence(),
                why.isEmpty() || why.equals(List.of("Check the amount.")) && account != null, List.copyOf(why), body, replaces, "/transactions");
    }

    private Proposal budget(CurrentUser user, AiContexts.Space ctx, JsonNode a, Integer replaces) {
        String name = AiReader.text(a, "category");
        UUID category = ctx.category("EXPENSE", name);
        Long amount = AiReader.minor(AiReader.text(a, "amount"));
        if (amount == null || amount <= 0) {
            throw new IllegalArgumentException("no amount");
        }
        YearMonth month;
        try {
            month = YearMonth.parse(orEmpty(AiReader.text(a, "month")));
        } catch (RuntimeException e) {
            month = YearMonth.from(ctx.context().today());
        }
        List<String> why = new ArrayList<>();
        if (category == null) {
            why.add("Which category?");
        }
        Long previous = null;
        if (category != null) {
            UUID c = category;
            previous = budgets.month(user, user.personalSpaceId(), month).budgets().stream().filter(b -> b.categoryId().equals(c))
                    .map(BudgetService.Budget::limit).findFirst().orElse(null);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryId", category);
        body.put("amount", amount);
        body.put("from", month.toString());
        body.put("previous", previous);
        String summary = Reminders.money(amount, ctx.currency()) + " a month from " + month.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)
                + (previous != null ? " (was " + Reminders.money(previous, ctx.currency()) + ")" : "");
        return new Proposal(UUID.randomUUID().toString(), "BUDGET", "Budget: " + (name != null ? name : "?"), summary, amount, ctx.currency(),
                confidence(a), why.isEmpty(), List.copyOf(why), body, replaces, "/budgets");
    }

    private Proposal bill(CurrentUser user, AiContexts.Space ctx, JsonNode a, Integer replaces) {
        String name = orEmpty(AiReader.text(a, "name"));
        Long amount = AiReader.minor(AiReader.text(a, "amount"));
        if (name.isBlank() || amount == null || amount <= 0) {
            throw new IllegalArgumentException("no bill");
        }
        int every = a.path("everyMonths").asInt(1);
        if (!List.of(1, 2, 3, 4, 6, 12).contains(every)) {
            every = 1;
        }
        LocalDate first;
        try {
            first = LocalDate.parse(orEmpty(AiReader.text(a, "firstDue")));
        } catch (RuntimeException e) {
            first = ctx.context().today().plusMonths(1).withDayOfMonth(1);
        }
        UUID account = ctx.account(AiReader.text(a, "account"));
        UUID category = ctx.category("EXPENSE", AiReader.text(a, "category"));
        if (category == null) {
            CategoryRuleService.Guess g = rules.guess(user, user.personalSpaceId(), "EXPENSE", name);
            category = g != null ? g.categoryId() : ctx.category("EXPENSE", "Other");
        }
        List<String> why = new ArrayList<>();
        if (account == null) {
            why.add("Paid from which account?");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "BILL");
        body.put("name", name.length() > 60 ? name.substring(0, 60) : name);
        body.put("amount", amount);
        body.put("accountId", account);
        body.put("categoryId", category);
        body.put("everyMonths", every);
        body.put("firstDue", first.toString());
        String often = switch (every) {
            case 1 -> "monthly";
            case 3 -> "every 3 months";
            case 6 -> "every 6 months";
            case 12 -> "yearly";
            default -> "every " + every + " months";
        };
        return new Proposal(UUID.randomUUID().toString(), "BILL", "Bill: " + name,
                Reminders.money(amount, ctx.currency()) + " " + often + ", next on " + first.format(DAY), amount, ctx.currency(), confidence(a),
                why.isEmpty(), List.copyOf(why), body, replaces, "/plan");
    }

    private Proposal payBill(AiContexts.Space ctx, Known known, JsonNode a, Integer replaces) {
        String name = AiReader.text(a, "bill");
        UUID id = name == null ? null : known.billIds().get(name);
        if (id == null) {
            throw new IllegalArgumentException("unknown bill");
        }
        LocalDate today = ctx.context().today();
        // The earliest unpaid due date up to a month ahead, else the latest one.
        record D(LocalDate dueDate, long amount, String currency) {
        }
        List<D> due = jdbc.sql("""
                        select d.due_date, c.amount, a.currency from commitments c join money_accounts a on a.id = c.account_id,
                        lateral (select cast(g as date) as due_date from generate_series(c.first_due, cast(:until as date),
                                 make_interval(months => c.every_months)) g) d
                        where c.id = :id and d.due_date >= cast(:from as date)
                          and not exists (select 1 from commitment_payments p where p.commitment_id = c.id and p.due_date = d.due_date)
                        order by d.due_date limit 1
                        """).param("id", id).param("from", today.minusMonths(3)).param("until", today.plusMonths(1))
                .query(D.class).list();
        if (due.isEmpty()) {
            throw new IllegalArgumentException("nothing due");
        }
        D d = due.getFirst();
        Long amount = AiReader.minor(AiReader.text(a, "amount"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commitmentId", id);
        body.put("dueDate", d.dueDate().toString());
        body.put("amount", amount);
        String date = AiReader.text(a, "date");
        body.put("occurredOn", date);
        return new Proposal(UUID.randomUUID().toString(), "PAY_BILL", "Paid: " + name,
                Reminders.money(amount != null ? amount : d.amount(), d.currency()) + " due " + d.dueDate().format(DAY), amount != null ? amount : d.amount(),
                d.currency(), confidence(a), true, List.of(), body, replaces, "/plan");
    }

    private Proposal goal(AiContexts.Space ctx, JsonNode a, Integer replaces) {
        String name = orEmpty(AiReader.text(a, "name"));
        Long amount = AiReader.minor(AiReader.text(a, "amount"));
        if (name.isBlank() || amount == null || amount <= 0) {
            throw new IllegalArgumentException("no goal");
        }
        LocalDate by = null;
        try {
            by = LocalDate.parse(orEmpty(AiReader.text(a, "by")));
        } catch (RuntimeException ignored) {
            // no date: a goal without a deadline
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name.length() > 60 ? name.substring(0, 60) : name);
        body.put("target", amount);
        body.put("targetDate", by == null ? null : by.toString());
        return new Proposal(UUID.randomUUID().toString(), "GOAL", "Goal: " + name,
                Reminders.money(amount, ctx.currency()) + (by != null ? " by " + by.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)) : ""),
                amount, ctx.currency(), confidence(a), true, List.of(), body, replaces, "/goals");
    }

    // ---------------------------------------------------------------- answers, from the data

    private String spending(UUID space, AiContexts.Space ctx, JsonNode a) {
        boolean earned = "earned".equalsIgnoreCase(AiReader.text(a, "metric"));
        LocalDate today = ctx.context().today();
        LocalDate from = date(AiReader.text(a, "from"), today.withDayOfMonth(1));
        LocalDate to = date(AiReader.text(a, "to"), today);
        String categoryName = AiReader.text(a, "category");
        UUID category = categoryName == null ? null : ctx.category(earned ? "INCOME" : "EXPENSE", categoryName);
        String merchant = AiReader.text(a, "merchant");
        long total = jdbc.sql("""
                        select coalesce(sum(t.amount), 0) from transactions t left join categories c on c.id = t.category_id
                        where t.space_id = :s and t.deleted_at is null and t.kind = :k and t.occurred_on between :from and :to
                          and (cast(:cat as uuid) is null or t.category_id = :cat or c.parent_id = :cat)
                          and (cast(:m as text) is null or t.description ilike :like or t.counterparty ilike :like)
                        """).param("s", space).param("k", earned ? "INCOME" : "EXPENSE").param("from", from).param("to", to)
                .param("cat", category).param("m", merchant).param("like", merchant == null ? null : "%" + merchant + "%")
                .query(Long.class).single();
        String what = categoryName != null && category != null ? " on " + categoryName : merchant != null ? " at " + merchant : "";
        return "You " + (earned ? "received " : "spent ") + Reminders.money(total, ctx.currency()) + what + " from " + from.format(DAY) + " to " + to.format(DAY) + ".";
    }

    private String budgetStatus(CurrentUser user, AiContexts.Space ctx, JsonNode a) {
        String name = AiReader.text(a, "category");
        UUID category = name == null ? null : ctx.category("EXPENSE", name);
        BudgetService.Month m = budgets.month(user, user.personalSpaceId(), null);
        List<BudgetService.Budget> lines = m.budgets().stream().filter(b -> category == null || b.categoryId().equals(category)).toList();
        if (lines.isEmpty()) {
            return name != null ? "There's no budget for " + name + " this month." : "You haven't set any budgets this month.";
        }
        long limit = lines.stream().mapToLong(BudgetService.Budget::limit).sum();
        long spent = lines.stream().mapToLong(b -> b.spent() == null ? 0 : b.spent()).sum();
        String what = name != null ? name + ": " : "All budgets: ";
        return what + Reminders.money(Math.max(0, limit - spent), ctx.currency()) + " left of " + Reminders.money(limit, ctx.currency())
                + (spent > limit ? ", over by " + Reminders.money(spent - limit, ctx.currency()) : "") + ".";
    }

    // ---------------------------------------------------------------- small things

    private static String page(String name) {
        if (name == null) {
            return null;
        }
        return switch (name.toLowerCase(Locale.ROOT).strip()) {
            case "dashboard", "transactions", "budgets", "plan", "accounts", "categories", "goals", "reports", "groups", "capture", "settings",
                 "utilities", "progress" -> "/" + name.toLowerCase(Locale.ROOT).strip();
            default -> null;
        };
    }

    private static double confidence(JsonNode a) {
        return Math.max(0, Math.min(1, a.path("confidence").asDouble(0.8)));
    }

    private static LocalDate date(String s, LocalDate fallback) {
        try {
            return s == null ? fallback : LocalDate.parse(s);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String day(LocalDate d, LocalDate today) {
        return d.equals(today) ? "today" : d.equals(today.minusDays(1)) ? "yesterday" : d.format(DAY);
    }

    private static List<String> nonNull(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                out.add(p);
            }
        }
        return out;
    }

    private static String join(List<String> names) {
        return names == null || names.isEmpty() ? "(none)" : String.join(" | ", names);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    static class NothingSaid extends ApiException {
        NothingSaid() {
            super(HttpStatus.BAD_REQUEST, "nothing-said", "say what you'd like to do");
        }
    }
}
