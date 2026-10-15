package dev.payledger.core.capture;

import dev.payledger.core.common.InvalidRequestException;
import dev.payledger.core.common.NotFoundException;
import dev.payledger.core.common.Uuid7;
import dev.payledger.core.money.CategoryRuleService;
import dev.payledger.core.money.Transaction;
import dev.payledger.core.money.TransactionRequests;
import dev.payledger.core.money.TransactionService;
import dev.payledger.core.notify.Inbox;
import dev.payledger.core.user.CurrentUser;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Entries that arrive without typing: bank alerts forwarded to your own address, and SMS you paste or share. Each is
 * read by {@link AlertParser} and waits in "To review" with a guessed account and category; only when you add it
 * does it become a transaction. Nothing is read from your mailbox or phone: you send it to us.
 */
@Service
public class CaptureService {

    private static final Logger log = LoggerFactory.getLogger(CaptureService.class);
    /** Enough for any alert; longer texts are cut so one paste can't fill the database. */
    static final int MAX_BODY = 4000;
    static final int MAX_PER_DAY = 500;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern GMAIL_CODE = Pattern.compile("Confirmation code:\\s*([0-9]{6,12})");
    private static final Pattern GMAIL_LINK = Pattern.compile("(https://mail(?:-settings)?\\.google\\.com/mail/\\S+)");

    private final JdbcClient jdbc;
    private final TransactionService transactions;
    private final CategoryRuleService rules;
    private final Inbox inbox;
    private final CaptureLearning learning;
    private final String domain;
    private final boolean inboundEnabled;

    CaptureService(JdbcClient jdbc, TransactionService transactions, CategoryRuleService rules, Inbox inbox, CaptureLearning learning,
                   @Value("${payledger.capture.domain:in.payledger.local}") String domain,
                   @Value("${payledger.capture.inbound-secret:}") String secret) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.rules = rules;
        this.inbox = inbox;
        this.learning = learning;
        this.domain = domain;
        // Forwarding works only with the Email Worker's secret and a real domain (not the .local placeholder): until
        // then the page doesn't hand out an address that can't receive mail.
        this.inboundEnabled = !secret.isBlank() && !domain.endsWith(".local");
    }

    /** @param accountId the account it most likely came from (its bank or last digits in the account's name) */
    public record Item(UUID id, String source, String status, String sender, String subject, String body, String kind, Long amount,
                       LocalDate occurredOn, String merchant, String bank, String accountHint, Long balance, UUID transactionId,
                       Instant receivedAt, UUID accountId, UUID categoryId, String categorySource, UUID duplicateOf, String readBy, String learned) {

        Item withLearned(String lesson) {
            return new Item(id, source, status, sender, subject, body, kind, amount, occurredOn, merchant, bank, accountHint, balance, transactionId,
                    receivedAt, accountId, categoryId, categorySource, duplicateOf, readBy, lesson);
        }
    }

    /**
     * @param emailReady the forwarding address works here (the inbound email door is configured)
     * @param confirmation Gmail's latest forwarding confirmation, while it's fresh (a week)
     */
    /**
     * @param forwardingOn Gmail forwarding works: an email arrived through it after Gmail's confirmation (or with no
     *                     confirmation on record), or the person said they confirmed it. Gmail itself never tells us.
     */
    public record View(String address, boolean emailReady, Confirmation confirmation, long pending, List<Item> items, Instant lastEmailAt,
                       boolean forwardingOn) {
    }

    public record Confirmation(String code, String link, Instant receivedAt) {
    }

    public record Pasted(int added, int duplicates, int unreadable, List<Item> items) {
    }

    // ---------------------------------------------------------------- what the person sees

    @Transactional
    public View view(CurrentUser user) {
        String token = token(user.id());
        record Conf(String confirmationCode, String confirmationLink, Instant confirmationAt, Instant forwardingConfirmedAt, Instant addressSince) {
        }
        Conf c = jdbc.sql("select confirmation_code, confirmation_link, confirmation_at, forwarding_confirmed_at, address_since from capture_addresses where user_id = :u")
                .param("u", user.id()).query(Conf.class).single();
        Confirmation confirmation = c.confirmationAt() != null && c.confirmationAt().isAfter(Instant.now().minusSeconds(7 * 86_400))
                ? new Confirmation(c.confirmationCode(), c.confirmationLink(), c.confirmationAt()) : null;
        List<Item> items = items(user, """
                select * from captured_items where user_id = :u and (status in ('PENDING', 'UNREADABLE') or decided_at > now() - interval '2 days')
                order by status = 'PENDING' desc, received_at desc, id desc limit 300
                """);
        long pending = jdbc.sql("select count(*) from captured_items where user_id = :u and status = 'PENDING'").param("u", user.id())
                .query(Long.class).single();
        Instant last = jdbc.sql("select max(received_at) from captured_items where user_id = :u and source = 'EMAIL'").param("u", user.id())
                .query(Instant.class).optional().orElse(null);
        // An email through this address after Gmail's confirmation (or after the address was made, with none on record).
        Instant after = c.confirmationAt() != null ? c.confirmationAt() : c.addressSince();
        boolean on = c.forwardingConfirmedAt() != null || last != null && last.isAfter(after);
        return new View(token + "@" + domain, inboundEnabled, confirmation, pending, items, last, on);
    }

    /** "I've confirmed it in Gmail": Gmail doesn't tell us, so the person does. */
    @Transactional
    public View forwardingConfirmed(CurrentUser user) {
        token(user.id());
        jdbc.sql("update capture_addresses set forwarding_confirmed_at = now() where user_id = :u").param("u", user.id()).update();
        return view(user);
    }

    /** How many wait to be reviewed: for the badge in the menu, so it's one cheap count. */
    @Transactional(readOnly = true)
    public long pending(CurrentUser user) {
        return jdbc.sql("select count(*) from captured_items where user_id = :u and status = 'PENDING'").param("u", user.id()).query(Long.class).single();
    }

    /** A new address: the old one stops working at once (if it leaked, or you just want a fresh one). */
    @Transactional
    public View resetAddress(CurrentUser user) {
        token(user.id());
        jdbc.sql("update capture_addresses set token = :t, confirmation_code = null, confirmation_link = null, confirmation_at = null, forwarding_confirmed_at = null, address_since = now() where user_id = :u")
                .param("t", newToken()).param("u", user.id()).update();
        return view(user);
    }

    /** Reads pasted or shared text: each message becomes an item, unless it was seen before or isn't a transaction at all. */
    @Transactional
    public Pasted paste(CurrentUser user, String text, String source) {
        if (text == null || text.isBlank()) {
            throw new InvalidRequestException("nothing-to-read", "paste one or more bank messages");
        }
        if (text.length() > 100_000) {
            throw new InvalidRequestException("too-long", "paste at most about 200 messages at a time");
        }
        int added = 0;
        int duplicates = 0;
        int unreadable = 0;
        List<UUID> ids = new ArrayList<>();
        LocalDate today = today(user.id());
        for (String message : AlertParser.split(text)) {
            CaptureLearning.Read read = learning.read(user.id(), message, null, today);
            AlertParser.Parsed p = read.parsed();
            if (p == null && (AlertParser.noise(message) || !looksLikeMoney(message))) {
                continue;
            }
            UUID id = store(user.id(), user.personalSpaceId(), source, null, null, message, fingerprint(AlertParser.clean(message)), p, null, null, read.by());
            if (id == null) {
                duplicates++;
            } else {
                ids.add(id);
                if (p == null) {
                    unreadable++;
                } else {
                    added++;
                }
            }
        }
        List<Item> items = ids.isEmpty() ? List.of() : items(user, "select * from captured_items where id in (:ids) order by received_at, id", ids);
        return new Pasted(added, duplicates, unreadable, items);
    }

    /** What you checked becomes a transaction; the original text is then let go. */
    /** @param counterparty who it was with: who paid you, or whom you paid (optional) */
    public record Add(String kind, Long amount, UUID accountId, UUID categoryId, LocalDate occurredOn, String description, String counterparty) {
    }

    @Transactional
    public Item add(CurrentUser user, UUID id, Add a) {
        Item item = own(user, id);
        if (!"PENDING".equals(item.status()) && !"UNREADABLE".equals(item.status())) {
            throw new InvalidRequestException("already-decided", "this one was already added or dismissed");
        }
        String description = a.description() != null && !a.description().isBlank() ? a.description().strip()
                : item.merchant() != null ? item.merchant() : item.bank() != null ? item.bank() : "From a bank alert";
        String kind = a.kind() != null ? a.kind() : item.kind();
        // No category chosen: "Other", so one tap is enough; it can be changed later like any entry.
        UUID category = a.categoryId() != null ? a.categoryId() : jdbc.sql("""
                        select id from categories where space_id = :s and kind = :k and not hidden
                        order by name = 'Other' desc, parent_id nulls first, sort_order desc limit 1
                        """).param("s", user.personalSpaceId()).param("k", kind).query(UUID.class).optional().orElse(null);
        Transaction t = transactions.create(user, user.personalSpaceId(), new TransactionRequests.Create(
                null, kind, a.amount() != null ? a.amount() : item.amount(), a.accountId(), null,
                category, a.occurredOn() != null ? a.occurredOn() : item.occurredOn(),
                description.length() > 120 ? description.substring(0, 120) : description, null, List.of("captured"), null,
                a.counterparty() == null || a.counterparty().isBlank() ? null : a.counterparty().strip())).transaction();
        // Your answer teaches it: a misread (or unread) alert becomes a pattern, a corrected name an alias.
        String lesson = item.source().equals("STATEMENT") ? null : learning.learn(user.id(), item.body(),
                "PENDING".equals(item.status()) && item.amount() != null
                        ? new AlertParser.Parsed(item.kind(), item.amount(), item.occurredOn(), item.merchant(), item.bank(), item.accountHint(), item.balance())
                        : null,
                kind, t.amount(), description);
        jdbc.sql("update captured_items set status = 'ADDED', transaction_id = :t, body = null, decided_at = now() where id = :id")
                .param("t", t.id()).param("id", id).update();
        return own(user, id).withLearned(lesson);
    }

    @Transactional
    public Item dismiss(CurrentUser user, UUID id) {
        own(user, id);
        jdbc.sql("update captured_items set status = 'DISMISSED', body = null, decided_at = now() where id = :id and status in ('PENDING', 'UNREADABLE')")
                .param("id", id).update();
        return own(user, id);
    }

    // ---------------------------------------------------------------- email from the Cloudflare Worker

    /** @return what happened, for the Worker's log */
    @Transactional
    public String inbound(String envelopeTo, byte[] raw) {
        String token = envelopeTo == null ? "" : envelopeTo.strip().toLowerCase(Locale.ROOT).replaceAll("^<|>$", "");
        int at = token.indexOf('@');
        if (at <= 0) {
            return "no-recipient";
        }
        // Gmail adds +labels for some forwards: the token is before any "+".
        token = token.substring(0, at).replaceAll("\\+.*$", "");
        record Owner(UUID userId, UUID spaceId) {
        }
        Owner owner = jdbc.sql("""
                        select a.user_id, s.id as space_id from capture_addresses a join users u on u.id = a.user_id and u.disabled_at is null
                        join spaces s on s.created_by = a.user_id and s.kind = 'PERSONAL' where a.token = :t
                        """).param("t", token).query(Owner.class).optional().orElse(null);
        if (owner == null) {
            return "unknown-address";
        }
        MimeMessage mail;
        String text;
        String from;
        String subject;
        String messageId;
        try {
            mail = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(raw));
            text = text(mail);
            from = mail.getFrom() != null && mail.getFrom().length > 0 ? ((InternetAddress) mail.getFrom()[0]).getAddress() : null;
            subject = mail.getSubject();
            messageId = mail.getMessageID();
        } catch (Exception e) {
            log.warn("Inbound email unreadable: {}", e.getMessage());
            return "unreadable";
        }
        if (from != null && from.toLowerCase(Locale.ROOT).startsWith("forwarding-noreply@google.com")) {
            Matcher code = GMAIL_CODE.matcher(text);
            Matcher link = GMAIL_LINK.matcher(text);
            jdbc.sql("""
                            update capture_addresses set confirmation_code = :code, confirmation_link = :link, confirmation_at = now() where user_id = :u
                            """).param("code", code.find() ? code.group(1) : null).param("link", link.find() ? link.group(1) : null)
                    .param("u", owner.userId()).update();
            inbox.notify(owner.userId(), Inbox.Kind.CAPTURED, "Gmail asked you to confirm forwarding", "Open Auto-capture and confirm with Gmail's link (or code) to finish.",
                    "/capture", null, "gmail-code:" + owner.userId() + ":" + Instant.now().getEpochSecond() / 600, null);
            return "gmail-confirmation";
        }
        long today = jdbc.sql("select count(*) from captured_items where user_id = :u and received_at > now() - interval '1 day'")
                .param("u", owner.userId()).query(Long.class).single();
        if (today >= MAX_PER_DAY) {
            return "over-daily-limit";
        }
        String clean = AlertParser.clean(text);
        if (AlertParser.noise((subject == null ? "" : subject + "\n") + clean)) {
            return "not-a-transaction";
        }
        CaptureLearning.Read read = learning.read(owner.userId(), (subject == null ? "" : subject + "\n") + clean, from, today(owner.userId()));
        AlertParser.Parsed p = read.parsed();
        String print = fingerprint(messageId != null ? messageId : clean);
        UUID id = store(owner.userId(), owner.spaceId(), "EMAIL", from, subject, clean, print, p, null, null, read.by());
        if (id == null) {
            return "duplicate";
        }
        // One bell for a burst of alerts, not one each.
        inbox.notify(owner.userId(), Inbox.Kind.CAPTURED, p != null ? "New entries to review" : "An email to look at",
                p != null ? (p.kind().equals("EXPENSE") ? "Spent " : "Received ") + dev.payledger.core.notify.Reminders.money(p.amount())
                        + (p.merchant() != null ? " · " + p.merchant() : "") : "We couldn't read it; you can add it by hand.",
                "/capture", null, "captured:" + owner.userId() + ":" + Instant.now().getEpochSecond() / 3600, null);
        return p != null ? "added" : "unreadable-kept";
    }

    /**
     * @param adds    the statement's own balances add up (opening + in − out = closing), or null when it can't be checked
     */
    public record Imported(int rows, int added, int duplicates, String bank, LocalDate from, LocalDate to, Long opening, Long closing, Boolean adds) {
    }

    /** Every row of a bank statement into To review, from the account chosen for the file. Re-uploading adds nothing. */
    @Transactional
    public Imported statement(CurrentUser user, byte[] file, String filename, UUID accountId, String password) {
        if (file == null || file.length == 0) {
            throw new InvalidRequestException("statement-empty", "choose a statement file");
        }
        if (file.length > 5 * 1024 * 1024) {
            throw new InvalidRequestException("statement-too-large", "a statement can be up to 5 MB; download a shorter period");
        }
        boolean mine = accountId != null && jdbc.sql("""
                        select exists (select 1 from money_accounts where id = :a and space_id = :s and archived_at is null and kind <> 'OWED')
                        """).param("a", accountId).param("s", user.personalSpaceId()).query(Boolean.class).single();
        if (!mine) {
            throw new InvalidRequestException("invalid-account", "choose which of your accounts the statement is for");
        }
        StatementParser.Statement st = StatementParser.parse(file, filename, password);
        String batch = (st.bank() != null ? st.bank() + " statement" : "Statement") + " · " + st.lines().getFirst().date() + " to " + st.lines().getLast().date();
        int added = 0;
        int seen = 0;
        int n = 0;
        for (StatementParser.Line l : st.lines()) {
            boolean in = l.in() > 0;
            long amount = in ? l.in() : l.out();
            // Position counts too: two identical coffees on one day are two rows.
            String print = fingerprint(accountId + "|" + l.date() + "|" + (in ? "IN" : "OUT") + "|" + amount + "|" + l.narration() + "|" + l.balance() + "|"
                    + (l.balance() == null ? n : ""));
            n++;
            // No clear name in the narration: the narration itself (shortened) says more than nothing.
            String who = learning.alias(user.id(), StatementParser.merchant(l.narration()));
            if (who == null) {
                String plain = l.narration().replaceAll("[0-9]{4,}", "").replaceAll("[/_*|-]+", " ").replaceAll("\\s+", " ").strip();
                who = plain.isEmpty() ? null : plain.length() > 60 ? plain.substring(0, 60) : plain;
            }
            AlertParser.Parsed p = new AlertParser.Parsed(in ? "INCOME" : "EXPENSE", amount, l.date(), who,
                    st.bank(), null, l.balance());
            UUID id = store(user.id(), user.personalSpaceId(), "STATEMENT", st.bank(), batch, l.narration(), print, p, accountId, batch);
            if (id == null) {
                seen++;
            } else {
                added++;
            }
        }
        return new Imported(st.lines().size(), added, seen, st.bank(), st.lines().getFirst().date(), st.lines().getLast().date(),
                st.opening(), st.closing(), st.adds());
    }

    public record AddedAll(int added, int skipped) {
    }

    /**
     * Adds every listed entry that's ready: an account known, and not looking like one already entered. The guessed
     * category is used (else Other). The rest stay for a closer look.
     */
    @Transactional
    public AddedAll addAll(CurrentUser user, List<UUID> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 1000) {
            throw new InvalidRequestException("invalid-items", "choose between 1 and 1,000 entries");
        }
        int added = 0;
        int skipped = 0;
        for (Item i : items(user, "select * from captured_items where id in (:ids) and user_id = :u and status = 'PENDING' order by occurred_on, received_at", ids)) {
            if (i.accountId() == null || i.duplicateOf() != null || i.amount() == null) {
                skipped++;
                continue;
            }
            add(user, i.id(), new Add(i.kind(), i.amount(), i.accountId(), i.categoryId(), i.occurredOn(),
                    i.merchant() != null ? i.merchant() : i.body() != null && i.body().length() <= 120 ? i.body() : null, null));
            added++;
        }
        return new AddedAll(added, ids.size() - added);
    }

    // ---------------------------------------------------------------- internals

    private UUID store(UUID userId, UUID spaceId, String source, String sender, String subject, String body, String print, AlertParser.Parsed p,
                       UUID accountId, String batch) {
        return store(userId, spaceId, source, sender, subject, body, print, p, accountId, batch, p == null ? null : "RULES");
    }

    private UUID store(UUID userId, UUID spaceId, String source, String sender, String subject, String body, String print, AlertParser.Parsed p,
                       UUID accountId, String batch, String readBy) {
        UUID id = Uuid7.next();
        String b = body.length() > MAX_BODY ? body.substring(0, MAX_BODY) : body;
        int n = jdbc.sql("""
                        insert into captured_items (id, user_id, space_id, source, status, sender, subject, body, fingerprint, kind, amount,
                                                    occurred_on, merchant, bank, account_hint, balance, account_id, batch, read_by)
                        values (:id, :u, :s, :source, :status, :sender, :subject, :body, :print, :kind, :amount, :on, :merchant, :bank, :hint, :balance,
                                :account, :batch, :readBy)
                        on conflict (user_id, fingerprint) do nothing
                        """)
                .param("id", id).param("u", userId).param("s", spaceId).param("source", source).param("status", p == null ? "UNREADABLE" : "PENDING")
                .param("sender", cut(sender, 254)).param("subject", cut(subject, 300)).param("body", b).param("print", print)
                .param("kind", p == null ? null : p.kind()).param("amount", p == null ? null : p.amount()).param("on", p == null ? null : p.on())
                .param("merchant", p == null ? null : cut(p.merchant(), 120)).param("bank", p == null ? null : p.bank())
                .param("hint", p == null ? null : p.accountHint()).param("balance", p == null ? null : p.balance())
                .param("account", accountId).param("batch", cut(batch, 120)).param("readBy", p == null ? null : readBy)
                .update();
        return n == 0 ? null : id;
    }

    private List<Item> items(CurrentUser user, String sql) {
        return items(user, sql, null);
    }

    private record Row(UUID id, String source, String status, String sender, String subject, String body, String kind, Long amount,
                       LocalDate occurredOn, String merchant, String bank, String accountHint, Long balance, UUID transactionId, Instant receivedAt,
                       UUID accountId, String readBy) {
    }

    private record Acct(UUID id, String name, String kind) {
    }

    /** Rows with their best guesses: the account (bank or last digits in its name), the category, and a twin already entered. */
    private List<Item> items(CurrentUser user, String sql, List<UUID> ids) {
        var q = jdbc.sql(sql).param("u", user.id());
        if (ids != null) {
            q = q.param("ids", ids);
        }
        List<Row> rows = q.query(Row.class).list();
        List<Acct> accounts = jdbc.sql("select id, name, kind from money_accounts where space_id = :s and archived_at is null and kind <> 'OWED' order by created_at")
                .param("s", user.personalSpaceId()).query(Acct.class).list();
        List<Item> out = new ArrayList<>();
        for (Row r : rows) {
            UUID account = null;
            UUID category = null;
            String categorySource = null;
            UUID twin = null;
            if ("PENDING".equals(r.status())) {
                // A statement's rows come from the account chosen for the file; an alert's is guessed.
                account = r.accountId() != null ? r.accountId() : guessAccount(accounts, r.bank(), r.accountHint());
                if (r.merchant() != null) {
                    CategoryRuleService.Guess g = rules.guess(user, user.personalSpaceId(), r.kind(), r.merchant());
                    if (g != null) {
                        category = g.categoryId();
                        categorySource = g.source();
                    }
                }
                // Typed in already? Same kind and amount within two days, not itself captured.
                twin = jdbc.sql("""
                                select id from transactions where space_id = :s and deleted_at is null and kind = :k and amount = :a
                                  and occurred_on between cast(:on as date) - 2 and cast(:on as date) + 2
                                  and not exists (select 1 from captured_items c where c.transaction_id = transactions.id)
                                order by abs(occurred_on - cast(:on as date)) limit 1
                                """).param("s", user.personalSpaceId()).param("k", r.kind()).param("a", r.amount())
                        .param("on", r.occurredOn() != null ? r.occurredOn() : LocalDate.now()).query(UUID.class).optional().orElse(null);
            }
            out.add(new Item(r.id(), r.source(), r.status(), r.sender(), r.subject(), r.body(), r.kind(), r.amount(), r.occurredOn(), r.merchant(),
                    r.bank(), r.accountHint(), r.balance(), r.transactionId(), r.receivedAt(), account, category, categorySource, twin, r.readBy(), null));
        }
        return out;
    }

    static UUID guessAccount(List<Acct> accounts, String bank, String hint) {
        if (hint != null) {
            for (Acct a : accounts) {
                if (a.name().contains(hint)) {
                    return a.id();
                }
            }
        }
        if (bank != null) {
            String first = bank.split(" ")[0].toLowerCase(Locale.ROOT);
            for (Acct a : accounts) {
                if (a.name().toLowerCase(Locale.ROOT).contains(first)) {
                    return a.id();
                }
            }
        }
        return null;
    }

    private Item own(CurrentUser user, UUID id) {
        return items(user, "select * from captured_items where id in (:ids) and user_id = :u", List.of(id)).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("captured-item"));
    }

    private String token(UUID userId) {
        return jdbc.sql("select token from capture_addresses where user_id = :u").param("u", userId).query(String.class).optional()
                .orElseGet(() -> {
                    String t = newToken();
                    jdbc.sql("insert into capture_addresses (user_id, token) values (:u, :t) on conflict (user_id) do nothing")
                            .param("u", userId).param("t", t).update();
                    return jdbc.sql("select token from capture_addresses where user_id = :u").param("u", userId).query(String.class).single();
                });
    }

    /** 12 letters and digits (about 60 bits): unguessable, and easy to read out. */
    private static String newToken() {
        String alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            s.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return s.toString();
    }

    private LocalDate today(UUID userId) {
        String zone = jdbc.sql("select time_zone from users where id = :u").param("u", userId).query(String.class).single();
        return LocalDate.now(ZoneId.of(zone));
    }

    private static boolean looksLikeMoney(String text) {
        return Pattern.compile("(?i)(rs\\.?|inr|₹)\\s*[0-9]|debited|credited").matcher(text).find();
    }

    private static String fingerprint(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.strip().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    /** The text of an email: its plain part, else its HTML part. */
    static String text(Part part) throws Exception {
        if (part.isMimeType("text/plain")) {
            return String.valueOf(part.getContent());
        }
        if (part.isMimeType("text/html")) {
            return AlertParser.clean(String.valueOf(part.getContent()));
        }
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            String html = null;
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart b = mp.getBodyPart(i);
                if (b.isMimeType("text/plain")) {
                    return String.valueOf(b.getContent());
                }
                String inner = text(b);
                if (!inner.isEmpty() && html == null) {
                    html = inner;
                }
            }
            return html == null ? "" : html;
        }
        if (part.isMimeType("message/rfc822")) {
            return text((Part) part.getContent());
        }
        return "";
    }
}
