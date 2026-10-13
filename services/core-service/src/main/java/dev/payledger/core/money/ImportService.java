package dev.payledger.core.money;

import dev.payledger.core.common.ApiException;
import dev.payledger.core.common.InvalidRequestException;
import dev.payledger.core.common.NotFoundException;
import dev.payledger.core.common.Uuid7;
import dev.payledger.core.space.SpaceAccess;
import dev.payledger.core.space.SpaceAccess.Space;
import dev.payledger.core.user.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Imports the template (product plan 2.5): every row is checked in a preview before anything is saved;
 * unknown accounts and categories are mapped to existing ones or created in the same step; rows already
 * there (same reference, or same account, date, amount, type and description) are skipped, so importing a
 * file twice adds nothing; and an import can be undone as a whole.
 */
@Service
public class ImportService {

    static final int MAX_ROWS = 2_000;
    /** Mapping value that creates the account or category instead of using an existing one. */
    static final String NEW = "new";

    private final JdbcClient jdbc;
    private final SpaceAccess access;
    private final AccountService accounts;
    private final CategoryService categories;
    private final TransactionService transactions;

    public ImportService(JdbcClient jdbc, SpaceAccess access, AccountService accounts, CategoryService categories,
                         TransactionService transactions) {
        this.jdbc = jdbc;
        this.access = access;
        this.accounts = accounts;
        this.categories = categories;
        this.transactions = transactions;
    }

    public enum Status { READY, DUPLICATE, ERROR }

    public record Problem(int line, String column, String reason) {
    }

    /** One row as it will be imported (names as written in the file). */
    public record Row(int line, Status status, List<Problem> problems, String kind, Long amount, LocalDate date,
                      String account, String toAccount, String category, String description) {
    }

    /**
     * A name in the file that matches nothing in the space. {@code key} is what the mapping uses. It stays
     * listed once mapped, so a client can show every choice while the user makes them.
     *
     * @param kind ACCOUNT, or EXPENSE / INCOME for a category
     */
    public record Unknown(String key, String name, String kind, String parent, int rows) {
    }

    public record Preview(int total, int ready, int duplicates, int errors, List<Row> rows,
                          List<Unknown> unknownAccounts, List<Unknown> unknownCategories) {
    }

    public record Result(UUID importId, int imported, int duplicates, int skipped) {
    }

    public record Import(UUID id, String fileName, int imported, int duplicates, int skipped, Instant createdAt, boolean undone) {
    }

    /**
     * @param accounts   unknown account key (its lower-case name) -> an account id, or {@code new:BANK} (CASH, CARD, WALLET)
     * @param categories unknown category key -> a category id, or {@code new}
     */
    /** @param xlsx an Excel workbook, base64-encoded, instead of csv */
    public record Request(String fileName, String csv, String xlsx, Map<String, String> accounts, Map<String, String> categories) {

        Map<String, String> accountMap() {
            return accounts == null ? Map.of() : accounts;
        }

        Map<String, String> categoryMap() {
            return categories == null ? Map.of() : categories;
        }
    }

    @Transactional(readOnly = true)
    public Preview preview(CurrentUser user, UUID spaceId, Request request) {
        Space space = access.read(user, spaceId);
        return check(space, lines(request), request, null);
    }

    @Transactional
    public Result commit(CurrentUser user, UUID spaceId, Request request) {
        Space space = access.write(user, spaceId);
        // One import at a time per space, so two uploads of the same file can't both pass the duplicate check.
        jdbc.sql("select id from spaces where id = :id for update").param("id", spaceId).query(UUID.class).single();
        List<ImportParser.Line> lines = lines(request);
        Preview first = check(space, lines, request, null);
        Created created = createMapped(user, space, first, request);
        Preview preview = check(space, lines, request, created);
        if (preview.ready() == 0) {
            throw new InvalidRequestException("nothing-to-import", preview.duplicates() > 0
                    ? "every row is already there" : "no row can be imported; see the preview for why");
        }

        UUID importId = Uuid7.next();
        jdbc.sql("""
                        insert into imports (id, space_id, file_name, imported, duplicates, skipped, created_by)
                        values (:id, :spaceId, :fileName, :imported, :duplicates, :skipped, :userId)
                        """)
                .param("id", importId).param("spaceId", spaceId).param("fileName", fileName(request))
                .param("imported", preview.ready()).param("duplicates", preview.duplicates()).param("skipped", preview.errors())
                .param("userId", user.id())
                .update();
        Map<Integer, ImportParser.Line> byLine = new HashMap<>();
        lines.forEach(l -> byLine.put(l.number(), l));
        Resolver resolver = new Resolver(space, request, created);
        Map<UUID, MoneyAccount> accountsById = new HashMap<>();
        accounts.all(spaceId).forEach(a -> accountsById.put(a.id(), a));
        Map<UUID, Category> categoriesById = new HashMap<>();
        categories.all(spaceId).forEach(c -> categoriesById.put(c.id(), c));
        for (Row row : preview.rows()) {
            if (row.status() != Status.READY) {
                continue;
            }
            Resolved r = resolver.resolve(byLine.get(row.line()));
            var fields = new TransactionRequests.Fields(r.kind, r.amount, r.accountId, r.toAccountId, r.categoryId, r.date,
                    r.description, r.note, r.tags);
            transactions.createChecked(space, user, Uuid7.next(), fields, accountsById.get(r.accountId),
                    r.toAccountId == null ? null : accountsById.get(r.toAccountId),
                    r.categoryId == null ? null : categoriesById.get(r.categoryId), importId, r.externalId);
        }
        return new Result(importId, preview.ready(), preview.duplicates(), preview.errors());
    }

    @Transactional(readOnly = true)
    public List<Import> list(CurrentUser user, UUID spaceId) {
        access.read(user, spaceId);
        return jdbc.sql("""
                        select id, file_name, imported, duplicates, skipped, created_at, undone_at is not null as undone
                        from imports where space_id = :spaceId order by created_at desc limit 50
                        """)
                .param("spaceId", spaceId)
                .query(Import.class)
                .list();
    }

    /** Deletes what the import added (the ledger reverses each), including rows edited since. */
    @Transactional
    public void undo(CurrentUser user, UUID spaceId, UUID importId) {
        access.write(user, spaceId);
        Boolean undone = jdbc.sql("select undone_at is not null from imports where id = :id and space_id = :spaceId for update")
                .param("id", importId).param("spaceId", spaceId)
                .query(Boolean.class)
                .optional()
                .orElseThrow(() -> new NotFoundException("import"));
        if (undone) {
            throw new UndoneException();
        }
        record Live(UUID id, int version) {
        }
        List<Live> live = jdbc.sql("select id, version from transactions where import_id = :id and deleted_at is null")
                .param("id", importId).query(Live.class).list();
        for (Live t : live) {
            transactions.delete(user, spaceId, t.id(), t.version());
        }
        jdbc.sql("update imports set undone_at = now() where id = :id").param("id", importId).update();
    }

    /** The template with this space's own account names in its example rows. */
    @Transactional(readOnly = true)
    public String template(CurrentUser user, UUID spaceId) {
        access.read(user, spaceId);
        StringBuilder csv = new StringBuilder(String.join(",", ImportXlsx.COLUMNS)).append("\r\n");
        for (List<String> row : examples(openAccounts(spaceId))) {
            csv.append(String.join(",", row.stream().map(ImportService::csvCell).toList())).append("\r\n");
        }
        return csv.toString();
    }

    /** The template as an Excel workbook, with dropdowns of this space's accounts and categories. */
    @Transactional(readOnly = true)
    public byte[] templateXlsx(CurrentUser user, UUID spaceId) {
        access.read(user, spaceId);
        List<String> names = openAccounts(spaceId);
        List<String> categoryNames = categories.all(spaceId).stream().filter(c -> !c.hidden()).map(Category::name).distinct().toList();
        return ImportXlsx.template(names, categoryNames, examples(names));
    }

    private List<String> openAccounts(UUID spaceId) {
        return jdbc.sql("select name from money_accounts where space_id = :spaceId and archived_at is null order by created_at")
                .param("spaceId", spaceId).query(String.class).list();
    }

    /** Three example rows (expense, income, transfer) in the template's column order. */
    private static List<List<String>> examples(List<String> accounts) {
        String account = accounts.isEmpty() ? "HDFC savings" : accounts.getFirst();
        String other = accounts.size() > 1 ? accounts.get(1) : "Cash";
        return List.of(
                List.of("05-10-2026", "486", "expense", account, "Groceries", "", "BigBasket order", "BigBasket", "UPI", "", "", "", "INR", ""),
                List.of("01-10-2026", "1,20,000", "income", account, "Salary", "", "October salary", "", "netbanking", "", "", "", "INR", ""),
                List.of("06-10-2026", "2000", "transfer", account, "", "", "Cash from ATM", "", "", other, "", "", "INR", ""));
    }

    // ---------------------------------------------------------------- checking rows

    private List<ImportParser.Line> lines(Request request) {
        List<ImportParser.Line> lines;
        if (request.xlsx() != null && !request.xlsx().isBlank()) {
            byte[] file;
            try {
                file = java.util.Base64.getDecoder().decode(request.xlsx());
            } catch (IllegalArgumentException e) {
                throw new InvalidRequestException("unreadable-file", "the Excel file didn't arrive intact; try again");
            }
            lines = ImportXlsx.read(file);
        } else if (request.csv() != null && !request.csv().isBlank()) {
            lines = ImportParser.csv(request.csv());
        } else {
            throw new InvalidRequestException("empty-import", "the file is empty");
        }
        if (lines.isEmpty()) {
            throw new InvalidRequestException("empty-import", "the file has a header but no rows");
        }
        if (lines.size() > MAX_ROWS) {
            throw new InvalidRequestException("import-too-large", "at most " + MAX_ROWS + " rows per import; split the file");
        }
        Set<String> columns = lines.getFirst().cells().keySet();
        if (!columns.contains("date") || (!columns.contains("amount") && !columns.contains("debit") && !columns.contains("credit"))
                || !columns.contains("account")) {
            throw new InvalidRequestException("import-columns-missing",
                    "the file needs date, amount (or debit and credit) and account columns; download the template to see them all");
        }
        return lines;
    }

    private Preview check(Space space, List<ImportParser.Line> lines, Request request, Created created) {
        Resolver resolver = new Resolver(space, request, created);
        List<Row> rows = new ArrayList<>();
        Set<String> seenRefs = new HashSet<>();
        Set<String> seenKeys = new HashSet<>();
        Existing existing = existing(space.id(), lines);
        for (ImportParser.Line line : lines) {
            Resolved r = resolver.resolve(line);
            Status status;
            if (!r.problems.isEmpty()) {
                status = Status.ERROR;
            } else if (r.externalId != null ? existing.refs.contains(r.externalId) || !seenRefs.add(r.externalId)
                    : existing.keys.contains(r.key()) || !seenKeys.add(r.key())) {
                status = Status.DUPLICATE;
            } else {
                status = Status.READY;
            }
            rows.add(new Row(line.number(), status, r.problems, r.kind, r.amount, r.date, line.get("account"),
                    line.get("to_account").isEmpty() ? null : line.get("to_account"), r.categoryName, r.description));
        }
        int ready = (int) rows.stream().filter(x -> x.status() == Status.READY).count();
        int duplicates = (int) rows.stream().filter(x -> x.status() == Status.DUPLICATE).count();
        return new Preview(rows.size(), ready, duplicates, rows.size() - ready - duplicates, rows,
                List.copyOf(resolver.unknownAccounts.values()), List.copyOf(resolver.unknownCategories.values()));
    }

    private record Existing(Set<String> refs, Set<String> keys) {
    }

    /** What is already in the space over the file's dates, to find duplicates without a query per row. */
    private Existing existing(UUID spaceId, List<ImportParser.Line> lines) {
        List<LocalDate> dates = lines.stream().map(l -> ImportParser.date(l.get("date"))).flatMap(Optional::stream).sorted().toList();
        // A transaction's own id counts as its reference too: an exported file carries it, so importing
        // an export back adds nothing.
        Set<String> refs = new HashSet<>(jdbc.sql("""
                        select external_id from transactions where space_id = :spaceId and external_id is not null and deleted_at is null
                        union all
                        select id::text from transactions where space_id = :spaceId and deleted_at is null
                        """)
                .param("spaceId", spaceId).query(String.class).list());
        Set<String> keys = new HashSet<>();
        if (!dates.isEmpty()) {
            jdbc.sql("""
                            select account_id, occurred_on, amount, kind, description from transactions
                            where space_id = :spaceId and deleted_at is null and occurred_on between :from and :to
                            """)
                    .param("spaceId", spaceId).param("from", dates.getFirst()).param("to", dates.getLast())
                    .query((rs, n) -> keys.add(key(rs.getObject("account_id", UUID.class), rs.getObject("occurred_on", LocalDate.class),
                            rs.getLong("amount"), rs.getString("kind"), rs.getString("description"))))
                    .list();
        }
        return new Existing(refs, keys);
    }

    private static String key(UUID account, LocalDate date, long amount, String kind, String description) {
        return account + "|" + date + "|" + amount + "|" + kind + "|" + description.strip().toLowerCase(Locale.ROOT);
    }

    /** One row, resolved to ids and values, with everything wrong with it. */
    private static final class Resolved {
        final List<Problem> problems = new ArrayList<>();
        String kind;
        Long amount;
        LocalDate date;
        UUID accountId;
        UUID toAccountId;
        UUID categoryId;
        String categoryName;
        String description;
        String note;
        List<String> tags = List.of();
        String externalId;

        String key() {
            return ImportService.key(accountId, date, amount, kind, description);
        }
    }

    /** Accounts and categories created during a commit, by mapping key. */
    private record Created(Map<String, UUID> accounts, Map<String, UUID> categories) {
    }

    private final class Resolver {
        final Space space;
        final Request request;
        final Created created;
        final Map<String, MoneyAccount> accountsByName = new HashMap<>();
        final Map<String, Category> categoriesByKey = new HashMap<>();
        final Map<UUID, Category> categoriesById = new HashMap<>();
        final Map<String, Unknown> unknownAccounts = new LinkedHashMap<>();
        final Map<String, Unknown> unknownCategories = new LinkedHashMap<>();

        Resolver(Space space, Request request, Created created) {
            this.space = space;
            this.request = request;
            this.created = created;
            for (MoneyAccount a : accounts.all(space.id())) {
                accountsByName.putIfAbsent(a.name().toLowerCase(Locale.ROOT), a);
            }
            for (Category c : categories.all(space.id())) {
                categoriesByKey.put(c.kind() + ":" + c.name().toLowerCase(Locale.ROOT), c);
                categoriesById.put(c.id(), c);
            }
        }

        Resolved resolve(ImportParser.Line line) {
            Resolved r = new Resolved();
            int n = line.number();

            r.date = ImportParser.date(line.get("date")).orElse(null);
            if (r.date == null) {
                r.problems.add(new Problem(n, "date", line.get("date").isEmpty() ? "missing" : "not a date: use DD-MM-YYYY"));
            }

            Long signed = null;
            String debit = line.get("debit");
            String credit = line.get("credit");
            String typeFromColumns = null;
            if (!line.get("amount").isEmpty()) {
                signed = amount(line.get("amount"), "amount", r, n);
            } else if (!debit.isEmpty() && !isZero(debit)) {
                signed = amount(debit, "debit", r, n);
                typeFromColumns = "EXPENSE";
            } else if (!credit.isEmpty() && !isZero(credit)) {
                signed = amount(credit, "credit", r, n);
                typeFromColumns = "INCOME";
            } else {
                r.problems.add(new Problem(n, "amount", "missing"));
            }

            r.kind = switch (line.get("type").toLowerCase(Locale.ROOT)) {
                case "expense", "debit", "dr", "spent" -> "EXPENSE";
                case "income", "credit", "cr", "received" -> "INCOME";
                case "transfer" -> "TRANSFER";
                case "" -> typeFromColumns != null ? typeFromColumns : signed != null && signed < 0 ? "EXPENSE" : "INCOME";
                default -> {
                    r.problems.add(new Problem(n, "type", "use expense, income or transfer"));
                    yield null;
                }
            };
            if (signed != null) {
                r.amount = Math.abs(signed);
                if (r.amount == 0) {
                    r.problems.add(new Problem(n, "amount", "is zero"));
                } else if (r.amount > AccountRequests.MAX_AMOUNT) {
                    r.problems.add(new Problem(n, "amount", "is too large"));
                }
            }

            MoneyAccount from = account(line.get("account"), "account", r, n);
            if (from != null) {
                r.accountId = from.id();
                String currency = line.get("currency");
                if (!currency.isEmpty() && !currency.equalsIgnoreCase(from.currency())) {
                    r.problems.add(new Problem(n, "currency", from.name() + " is in " + from.currency()));
                }
            }
            if ("TRANSFER".equals(r.kind)) {
                MoneyAccount to = account(line.get("to_account"), "to_account", r, n);
                if (to != null) {
                    r.toAccountId = to.id();
                    if (to.id().equals(r.accountId)) {
                        r.problems.add(new Problem(n, "to_account", "is the same account"));
                    }
                }
            } else if (r.kind != null) {
                category(line, r, n);
            }

            String description = Stream.of(line.get("description"), line.get("merchant"))
                    .filter(s -> !s.isEmpty()).findFirst()
                    .orElse(r.categoryName != null ? r.categoryName : "TRANSFER".equals(r.kind) ? "Transfer" : "Imported");
            r.description = description.length() > 120 ? description.substring(0, 120) : description;
            String notes = line.get("notes");
            r.note = notes.isEmpty() ? null : notes.length() > 1000 ? notes.substring(0, 1000) : notes;
            Set<String> tags = new LinkedHashSet<>();
            Arrays.stream(line.get("tags").split("[;|]")).map(String::strip).filter(s -> !s.isEmpty()).forEach(tags::add);
            if (!line.get("payment_mode").isEmpty()) {
                tags.add(line.get("payment_mode").toLowerCase(Locale.ROOT));
            }
            r.tags = tags.stream().map(t -> t.length() > 30 ? t.substring(0, 30) : t).limit(10).toList();
            String ref = line.get("external_id");
            if (ref.length() > 100) {
                r.problems.add(new Problem(n, "external_id", "longer than 100 characters"));
            } else if (!ref.isEmpty()) {
                r.externalId = ref;
            }
            return r;
        }

        private Long amount(String text, String column, Resolved r, int n) {
            Optional<Long> value = ImportParser.amount(text);
            if (value.isEmpty()) {
                r.problems.add(new Problem(n, column, "not an amount: use a number like 1,250.50"));
            }
            return value.orElse(null);
        }

        private boolean isZero(String text) {
            return ImportParser.amount(text).map(v -> v == 0).orElse(false);
        }

        private MoneyAccount account(String name, String column, Resolved r, int n) {
            if (name.isEmpty()) {
                r.problems.add(new Problem(n, column, "missing"));
                return null;
            }
            String key = name.toLowerCase(Locale.ROOT);
            MoneyAccount account = accountsByName.get(key);
            // A mapping only applies to a name the space doesn't have.
            if (account == null) {
                unknownAccounts.merge(key, new Unknown(key, name, "ACCOUNT", null, 1),
                        (a, b) -> new Unknown(a.key(), a.name(), a.kind(), null, a.rows() + 1));
                String mapped = request.accountMap().get(key);
                if (mapped == null) {
                    r.problems.add(new Problem(n, column, "no account called " + name + "; choose one or create it"));
                    return null;
                }
                if (mapped.startsWith(NEW + ":")) {
                    UUID id = created != null ? created.accounts().get(key) : null;
                    return id != null ? accounts.find(space.id(), id, false) : placeholder(name);
                }
                account = accounts.all(space.id()).stream().filter(a -> a.id().toString().equals(mapped)).findFirst().orElse(null);
                if (account == null) {
                    r.problems.add(new Problem(n, column, "mapped to an account that isn't there"));
                    return null;
                }
            }
            if (account.archived()) {
                r.problems.add(new Problem(n, column, account.name() + " is archived"));
                return null;
            }
            return account;
        }

        /** An account the commit will create: good enough to check the row with. */
        private MoneyAccount placeholder(String name) {
            return new MoneyAccount(new UUID(0, name.hashCode()), space.id(), AccountKind.BANK, name, space.currency(), 0,
                    LocalDate.now(), false, Instant.now());
        }

        /** Category, then subcategory within it; empty means "Other". */
        private void category(ImportParser.Line line, Resolved r, int n) {
            String name = line.get("category");
            String sub = line.get("subcategory");
            Category parent;
            if (name.isEmpty()) {
                parent = categoriesByKey.values().stream()
                        .filter(c -> c.kind().equals(r.kind) && c.name().equals("Other") && c.parentId() == null)
                        .findFirst().orElse(null);
                if (parent == null) {
                    r.problems.add(new Problem(n, "category", "missing"));
                    return;
                }
            } else {
                parent = category(r.kind + ":" + name.toLowerCase(Locale.ROOT), name, null, r, n);
            }
            if (parent == null) {
                return;
            }
            Category chosen = parent;
            if (!sub.isEmpty()) {
                Category child = categoriesByKey.get(r.kind + ":" + sub.toLowerCase(Locale.ROOT));
                if (child != null && parent.id().equals(child.parentId())) {
                    chosen = child;
                } else {
                    chosen = category(r.kind + ":" + parent.name().toLowerCase(Locale.ROOT) + "/" + sub.toLowerCase(Locale.ROOT),
                            sub, parent.name(), r, n);
                    if (chosen == null) {
                        return;
                    }
                }
            }
            if (chosen.hidden()) {
                r.problems.add(new Problem(n, sub.isEmpty() ? "category" : "subcategory", chosen.name() + " is hidden"));
                return;
            }
            r.categoryId = chosen.id();
            r.categoryName = chosen.name();
        }

        private Category category(String key, String name, String parentName, Resolved r, int n) {
            Category found = parentName == null ? categoriesByKey.get(key) : null;
            if (found != null) {
                return found;
            }
            unknownCategories.merge(key, new Unknown(key, name, r.kind, parentName, 1),
                    (a, b) -> new Unknown(a.key(), a.name(), a.kind(), a.parent(), a.rows() + 1));
            String column = parentName == null ? "category" : "subcategory";
            String mapped = request.categoryMap().get(key);
            if (mapped == null) {
                r.problems.add(new Problem(n, column,
                        "no " + r.kind.toLowerCase(Locale.ROOT) + " category called " + name + "; choose one or create it"));
                return null;
            }
            if (mapped.equals(NEW)) {
                UUID id = created != null ? created.categories().get(key) : null;
                return id != null ? categories.find(space.id(), id)
                        : new Category(new UUID(0, key.hashCode()), r.kind, name, "tag", "series-8", null, false);
            }
            Category target = categoriesById.values().stream().filter(c -> c.id().toString().equals(mapped)).findFirst().orElse(null);
            if (target == null || !target.kind().equals(r.kind)) {
                r.problems.add(new Problem(n, column, "mapped to a category that doesn't fit"));
                return null;
            }
            return target;
        }
    }

    // ---------------------------------------------------------------- creating mapped names

    /** Creates the accounts and categories mapped to "new" that some row uses. */
    private Created createMapped(CurrentUser user, Space space, Preview preview, Request request) {
        Map<String, UUID> newAccounts = new HashMap<>();
        Set<String> usedAccounts = new HashSet<>();
        preview.rows().forEach(row -> {
            usedAccounts.add(row.account().toLowerCase(Locale.ROOT));
            if (row.toAccount() != null) {
                usedAccounts.add(row.toAccount().toLowerCase(Locale.ROOT));
            }
        });
        Map<String, String> names = new HashMap<>();
        preview.rows().forEach(row -> {
            names.putIfAbsent(row.account().toLowerCase(Locale.ROOT), row.account());
            if (row.toAccount() != null) {
                names.putIfAbsent(row.toAccount().toLowerCase(Locale.ROOT), row.toAccount());
            }
        });
        Set<String> existing = new HashSet<>();
        accounts.all(space.id()).forEach(a -> existing.add(a.name().toLowerCase(Locale.ROOT)));
        for (var e : request.accountMap().entrySet()) {
            if (!e.getValue().startsWith(NEW + ":") || !usedAccounts.contains(e.getKey()) || existing.contains(e.getKey())) {
                continue;
            }
            AccountKind kind;
            try {
                kind = AccountKind.valueOf(e.getValue().substring(NEW.length() + 1));
            } catch (IllegalArgumentException ex) {
                throw new InvalidRequestException("invalid-mapping", e.getValue() + " is not new:BANK, new:CASH, new:CARD or new:WALLET");
            }
            String name = names.get(e.getKey());
            newAccounts.put(e.getKey(), accounts.create(user, space.id(), new AccountRequests.Create(kind, name, null, 0L, null)).id());
        }

        Map<String, UUID> newCategories = new HashMap<>();
        // Parents before subcategories ("expense:food" sorts before "expense:food/snacks").
        List<String> keys = request.categoryMap().entrySet().stream()
                .filter(e -> e.getValue().equals(NEW)).map(Map.Entry::getKey).sorted().toList();
        Map<String, Unknown> unknown = new HashMap<>();
        preview.unknownCategories().forEach(u -> unknown.put(u.key(), u));
        for (String key : keys) {
            Unknown u = unknown.get(key);
            if (u == null) {
                continue;
            }
            UUID parentId = null;
            if (u.parent() != null) {
                String parentKey = u.kind() + ":" + u.parent().toLowerCase(Locale.ROOT);
                parentId = newCategories.containsKey(parentKey) ? newCategories.get(parentKey)
                        : categories.all(space.id()).stream().filter(c -> (c.kind() + ":" + c.name().toLowerCase(Locale.ROOT)).equals(parentKey))
                        .map(Category::id).findFirst().orElse(null);
            }
            newCategories.put(key, categories.create(user, space.id(),
                    new CategoryRequests.Create(u.kind(), u.name(), "tag", "series-8", parentId)).id());
        }
        return new Created(newAccounts, newCategories);
    }

    private static String fileName(Request request) {
        String name = request.fileName() == null || request.fileName().isBlank() ? "import.csv" : request.fileName().strip();
        return name.length() > 200 ? name.substring(0, 200) : name;
    }

    private static String csvCell(String value) {
        return value.contains(",") || value.contains("\"") ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    static class UndoneException extends ApiException {
        UndoneException() {
            super(HttpStatus.CONFLICT, "import-undone", "this import was already undone");
        }
    }
}
