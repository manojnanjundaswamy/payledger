package dev.payledger.ledger.journal;

import dev.payledger.ledger.account.Account;
import dev.payledger.ledger.account.AccountRepository;
import dev.payledger.ledger.account.AccountType;
import dev.payledger.ledger.book.Book;
import dev.payledger.ledger.book.BookKind;
import dev.payledger.ledger.book.BookRepository;
import dev.payledger.ledger.common.EntryNotFoundException;
import dev.payledger.ledger.common.IdempotencyKeyReusedException;
import dev.payledger.ledger.common.InvalidPostingException;
import dev.payledger.ledger.common.RefConflictException;
import dev.payledger.ledger.common.ReversalNotAllowedException;
import dev.payledger.ledger.common.Uuid7;
import dev.payledger.ledger.outbox.OutboxWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The only component allowed to write to the ledger.
 * <p>
 * One call = one database transaction that:
 * <ol>
 *   <li>returns the stored result if the idempotency key was already used,</li>
 *   <li>creates the book and any accounts seen for the first time,</li>
 *   <li>locks every involved account row in a consistent order,</li>
 *   <li>applies the postings and updates cached balances,</li>
 *   <li>inserts the entry; the UNIQUE(idempotency_key) constraint catches concurrent duplicates,</li>
 *   <li>appends a {@link LedgerEntryPosted} event to the outbox, so the event commits if and only if the entry does.</li>
 * </ol>
 * Transactions are demarcated with {@link TransactionTemplate} rather than {@code @Transactional}
 * because the duplicate-key recovery needs a second, separate transaction after the first one
 * rolled back. That is awkward with proxies and self-invocation.
 */
@Service
public class LedgerService {

    static final String REVERSAL = "REVERSAL";
    private static final Pattern REF = Pattern.compile("[A-Za-z0-9:_.-]{1,128}");
    private static final Pattern ENTRY_TYPE = Pattern.compile("[A-Z_]{1,32}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private final BookRepository books;
    private final AccountRepository accounts;
    private final JournalEntryRepository entries;
    private final OutboxWriter outbox;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnlyTx;

    public LedgerService(BookRepository books, AccountRepository accounts, JournalEntryRepository entries,
                         OutboxWriter outbox, JdbcClient jdbc, PlatformTransactionManager txManager) {
        this.books = books;
        this.accounts = accounts;
        this.entries = entries;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.readOnlyTx = new TransactionTemplate(txManager);
        this.readOnlyTx.setReadOnly(true);
    }

    public PostingOutcome post(PostingCommand command) {
        validate(command);
        String hash = fingerprint(command);
        try {
            return tx.execute(status -> postInTransaction(command, hash));
        } catch (DataIntegrityViolationException e) {
            // Two requests with the same key raced. Both passed the "already exists?" check, and the
            // loser hit the unique constraint. Its transaction is rolled back, so nothing was recorded.
            // Return what the winner stored.
            return readOnlyTx.execute(status -> findExisting(command.idempotencyKey(), hash)).orElseThrow(() -> e);
        }
    }

    public PostingOutcome reverse(ReversalCommand command) {
        requireKey(command.idempotencyKey(), "idempotencyKey");
        requireKey(command.reversesIdempotencyKey(), "reversesIdempotencyKey");
        String hash = sha256(REVERSAL, command.reversesIdempotencyKey(), command.description());
        try {
            return tx.execute(status -> reverseInTransaction(command, hash));
        } catch (DataIntegrityViolationException e) {
            // Either the same reversal raced itself (return the winner's), or a reversal with a DIFFERENT
            // key got there first and the UNIQUE(reverses_entry_id) constraint stopped this one.
            return readOnlyTx.execute(status -> findExisting(command.idempotencyKey(), hash))
                    .orElseThrow(() -> new ReversalNotAllowedException(
                            "entry " + command.reversesIdempotencyKey() + " is already reversed"));
        }
    }

    private PostingOutcome postInTransaction(PostingCommand command, String hash) {
        Optional<PostingOutcome> existing = findExisting(command.idempotencyKey(), hash);
        if (existing.isPresent()) {
            return existing.get();
        }

        Book book = upsertBook(command.bookRef(), command.bookKind());
        Map<String, Account> locked = upsertAndLockAccounts(book, command.legs());

        var entry = new JournalEntry(book.getId(), command.entryType(), command.idempotencyKey(), hash,
                command.effectiveDate(), command.description(), command.sourceRef(), null);
        for (PostingCommand.Leg leg : command.legs()) {
            entry.post(locked.get(key(leg.accountRef(), leg.currency())), leg.amount());
        }
        return save(entry, book, locked.values());
    }

    private PostingOutcome reverseInTransaction(ReversalCommand command, String hash) {
        Optional<PostingOutcome> existing = findExisting(command.idempotencyKey(), hash);
        if (existing.isPresent()) {
            return existing.get();
        }

        JournalEntry original = entries.findByIdempotencyKey(command.reversesIdempotencyKey())
                .orElseThrow(() -> new EntryNotFoundException(command.reversesIdempotencyKey()));
        if (original.getReversesEntryId() != null) {
            throw new ReversalNotAllowedException("a reversal cannot be reversed; post a new entry instead");
        }
        if (entries.existsByReversesEntryId(original.getId())) {
            throw new ReversalNotAllowedException("entry " + command.reversesIdempotencyKey() + " is already reversed");
        }
        Book book = books.findById(original.getBookId()).orElseThrow();

        Map<UUID, Account> locked = accounts
                .lockAllByIdsOrdered(original.getPostings().stream().map(Posting::getAccountId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));

        String description = command.description() != null ? command.description()
                : "Reversal of " + command.reversesIdempotencyKey();
        var reversal = new JournalEntry(book.getId(), REVERSAL, command.idempotencyKey(), hash,
                original.getEffectiveDate(), description, original.getSourceRef(), original.getId());
        for (Posting posting : original.getPostings()) {
            reversal.post(locked.get(posting.getAccountId()), Math.negateExact(posting.getAmount()));
        }
        return save(reversal, book, locked.values());
    }

    private PostingOutcome save(JournalEntry entry, Book book, Iterable<Account> involved) {
        entries.saveAndFlush(entry);
        Map<UUID, Account> byId = new HashMap<>();
        involved.forEach(a -> byId.put(a.getId(), a));
        JournalEntryView view = JournalEntryView.of(entry, book.getRef(), byId);
        LedgerEntryPosted event = LedgerEntryPosted.of(view, book.getKind());
        // Keyed by book: every event of one book lands on one partition, in commit order.
        outbox.append("Book", book.getId(), event.eventId(), LedgerEntryPosted.TYPE, event);
        return new PostingOutcome(view, false);
    }

    private Book upsertBook(String ref, BookKind kind) {
        jdbc.sql("insert into books (id, ref, kind) values (:id, :ref, :kind) on conflict (ref) do nothing")
                .param("id", Uuid7.next())
                .param("ref", ref)
                .param("kind", kind.name())
                .update();
        Book book = books.findByRef(ref).orElseThrow();
        if (book.getKind() != kind) {
            throw new RefConflictException("book " + ref + " is a " + book.getKind() + " book, not " + kind);
        }
        return book;
    }

    /**
     * Creates missing accounts with {@code INSERT ... ON CONFLICT DO NOTHING} (safe when two first
     * postings race), then locks all of them in one ordered query.
     * <p>
     * Inserts are sorted too: an uncommitted insert blocks other inserts of the same key, so two
     * transactions creating {a, b} and {b, a} in request order could otherwise deadlock.
     */
    private Map<String, Account> upsertAndLockAccounts(Book book, List<PostingCommand.Leg> legs) {
        List<PostingCommand.Leg> sorted = legs.stream()
                .sorted(Comparator.comparing(PostingCommand.Leg::accountRef).thenComparing(PostingCommand.Leg::currency))
                .toList();
        for (PostingCommand.Leg leg : sorted) {
            jdbc.sql("""
                            insert into accounts (id, book_id, ref, type, currency)
                            values (:id, :bookId, :ref, :type, :currency)
                            on conflict (book_id, ref, currency) do nothing
                            """)
                    .param("id", Uuid7.next())
                    .param("bookId", book.getId())
                    .param("ref", leg.accountRef())
                    .param("type", leg.accountType().name())
                    .param("currency", leg.currency())
                    .update();
        }

        List<Account> locked = accounts.lockAllByBookAndRefsOrdered(book.getId(),
                legs.stream().map(PostingCommand.Leg::accountRef).distinct().toList());
        Map<String, AccountType> requestedTypes = legs.stream()
                .collect(Collectors.toMap(PostingCommand.Leg::accountRef, PostingCommand.Leg::accountType, (a, b) -> a));
        for (Account account : locked) {
            AccountType requested = requestedTypes.get(account.getRef());
            if (account.getType() != requested) {
                throw new RefConflictException("account " + account.getRef() + " in book " + book.getRef()
                        + " is " + account.getType() + ", not " + requested);
            }
        }
        return locked.stream().collect(Collectors.toMap(a -> key(a.getRef(), a.getCurrency()), Function.identity()));
    }

    private Optional<PostingOutcome> findExisting(String idempotencyKey, String hash) {
        return entries.findByIdempotencyKey(idempotencyKey).map(entry -> {
            if (!entry.getRequestHash().equals(hash)) {
                throw new IdempotencyKeyReusedException(idempotencyKey);
            }
            Book book = books.findById(entry.getBookId()).orElseThrow();
            Map<UUID, Account> involved = accounts
                    .findAllById(entry.getPostings().stream().map(Posting::getAccountId).distinct().toList())
                    .stream()
                    .collect(Collectors.toMap(Account::getId, Function.identity()));
            return new PostingOutcome(JournalEntryView.of(entry, book.getRef(), involved), true);
        });
    }

    private static void validate(PostingCommand command) {
        requireKey(command.idempotencyKey(), "idempotencyKey");
        requireMatch(command.bookRef(), REF, "bookRef");
        if (command.bookKind() == null || command.bookKind() == BookKind.LEGACY) {
            throw new InvalidPostingException("bookKind must be PERSONAL, HOUSEHOLD or GROUP");
        }
        requireMatch(command.entryType(), ENTRY_TYPE, "entryType");
        if (REVERSAL.equals(command.entryType())) {
            throw new InvalidPostingException("use the reversal endpoint to reverse an entry");
        }
        if (command.effectiveDate() == null) {
            throw new InvalidPostingException("effectiveDate is required");
        }
        if (command.legs() == null || command.legs().size() < 2) {
            throw new InvalidPostingException("a journal entry needs at least two postings");
        }

        Map<String, Long> sumPerCurrency = new TreeMap<>(); // sorted: the same bad request always gets the same error
        Map<String, AccountType> typePerRef = new HashMap<>();
        for (PostingCommand.Leg leg : command.legs()) {
            requireMatch(leg.accountRef(), REF, "accountRef");
            requireMatch(leg.currency(), CURRENCY, "currency");
            if (leg.accountType() == null || leg.accountType() == AccountType.USER || leg.accountType() == AccountType.SYSTEM) {
                throw new InvalidPostingException("accountType must be ASSET, LIABILITY, EQUITY, INCOME or EXPENSE");
            }
            if (leg.amount() == 0) {
                throw new InvalidPostingException("posting amounts must be non-zero");
            }
            AccountType previous = typePerRef.putIfAbsent(leg.accountRef(), leg.accountType());
            if (previous != null && previous != leg.accountType()) {
                throw new InvalidPostingException("account " + leg.accountRef() + " is given two different types");
            }
            sumPerCurrency.merge(leg.currency(), leg.amount(), Math::addExact);
        }
        sumPerCurrency.forEach((currency, sum) -> {
            if (sum != 0) {
                throw new InvalidPostingException(currency + " postings must sum to zero but sum to " + sum);
            }
        });
    }

    private static void requireKey(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new InvalidPostingException(field + " is required (at most 128 characters)");
        }
    }

    private static void requireMatch(String value, Pattern pattern, String field) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new InvalidPostingException(field + " is missing or invalid: " + value);
        }
    }

    private static String key(String accountRef, String currency) {
        return accountRef + "|" + currency;
    }

    /** SHA-256 of the business payload, so a reused key with different content is detected. */
    static String fingerprint(PostingCommand c) {
        String legs = c.legs().stream()
                .map(l -> l.accountRef() + "," + l.accountType() + "," + l.currency() + "," + l.amount())
                .collect(Collectors.joining(";"));
        return sha256(c.bookRef(), c.bookKind(), c.entryType(), c.effectiveDate(), c.description(), c.sourceRef(), legs);
    }

    private static String sha256(Object... parts) {
        String canonical = String.join("|", Arrays.stream(parts).map(p -> Objects.toString(p, "")).toList());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed by the JDK", e);
        }
    }
}
