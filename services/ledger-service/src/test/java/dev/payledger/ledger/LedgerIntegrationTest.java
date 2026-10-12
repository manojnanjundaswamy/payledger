package dev.payledger.ledger;

import dev.payledger.ledger.account.AccountType;
import dev.payledger.ledger.api.LedgerQueries;
import dev.payledger.ledger.api.LedgerQueries.Balance;
import dev.payledger.ledger.api.LedgerQueries.PeriodTotal;
import dev.payledger.ledger.book.BookKind;
import dev.payledger.ledger.common.IdempotencyKeyReusedException;
import dev.payledger.ledger.common.InvalidPostingException;
import dev.payledger.ledger.common.RefConflictException;
import dev.payledger.ledger.common.ReversalNotAllowedException;
import dev.payledger.ledger.journal.LedgerService;
import dev.payledger.ledger.journal.PostingCommand;
import dev.payledger.ledger.journal.PostingCommand.Leg;
import dev.payledger.ledger.journal.PostingOutcome;
import dev.payledger.ledger.journal.ReversalCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static dev.payledger.ledger.account.AccountType.ASSET;
import static dev.payledger.ledger.account.AccountType.EXPENSE;
import static dev.payledger.ledger.account.AccountType.INCOME;
import static dev.payledger.ledger.account.AccountType.LIABILITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LedgerIntegrationTest {

    static final LocalDate OCT_10 = LocalDate.of(2026, 10, 10);

    @Autowired LedgerService ledger;
    @Autowired LedgerQueries queries;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void entriesCreateTheirBookAndAccountsAndKeepEveryBalanceConsistent() {
        String book = newBook();

        post(book, "INCOME", OCT_10, leg("bank:hdfc", ASSET, 80_000_00), leg("income:salary", INCOME, -80_000_00));
        PostingOutcome groceries = post(book, "EXPENSE", OCT_10,
                leg("category:groceries", EXPENSE, 500_00), leg("card:icici", LIABILITY, -500_00));

        assertThat(groceries.entry().bookRef()).isEqualTo(book);
        assertThat(groceries.entry().postings())
                .extracting(p -> p.accountRef(), p -> p.amount(), p -> p.balanceAfter())
                .containsExactlyInAnyOrder(tuple("category:groceries", 500_00L, 500_00L), tuple("card:icici", -500_00L, -500_00L));
        assertThat(balances(book)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "bank:hdfc", 80_000_00L, "income:salary", -80_000_00L,
                "category:groceries", 500_00L, "card:icici", -500_00L));
        assertThat(balances(book).values().stream().mapToLong(Long::longValue).sum())
                .as("a book always balances to zero").isZero();
    }

    @Test
    void unbalancedEntriesAreRejectedPerCurrency() {
        String book = newBook();

        assertThatThrownBy(() -> post(book, "EXPENSE", OCT_10, leg("category:food", EXPENSE, 100), leg("bank:a", ASSET, -99)))
                .isInstanceOf(InvalidPostingException.class).hasMessageContaining("sum to 1");
        assertThatThrownBy(() -> ledger.post(new PostingCommand(key(), book, BookKind.PERSONAL, "EXPENSE", OCT_10, null, null,
                List.of(new Leg("category:food", EXPENSE, "INR", 100), new Leg("bank:a", ASSET, "USD", -100)))))
                .isInstanceOf(InvalidPostingException.class).hasMessageContaining("INR postings must sum to zero");
    }

    @Test
    void retryWithSameIdempotencyKeyReplaysInsteadOfRecordingTwice() {
        String book = newBook();
        String key = key();

        PostingOutcome first = postWithKey(key, book, leg("category:rent", EXPENSE, 25_000_00), leg("bank:a", ASSET, -25_000_00));
        PostingOutcome retry = postWithKey(key, book, leg("category:rent", EXPENSE, 25_000_00), leg("bank:a", ASSET, -25_000_00));

        assertThat(first.replayed()).isFalse();
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.entry().id()).isEqualTo(first.entry().id());
        assertThat(balances(book)).containsEntry("bank:a", -25_000_00L);
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejected() {
        String book = newBook();
        String key = key();
        postWithKey(key, book, leg("category:rent", EXPENSE, 100), leg("bank:a", ASSET, -100));

        assertThatThrownBy(() -> postWithKey(key, book, leg("category:rent", EXPENSE, 101), leg("bank:a", ASSET, -101)))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void concurrentDuplicateRequestsRecordExactlyOnce() throws Exception {
        String book = newBook();
        String key = key();

        List<PostingOutcome> outcomes = runConcurrently(20,
                () -> postWithKey(key, book, leg("category:food", EXPENSE, 100), leg("bank:a", ASSET, -100)));

        assertThat(outcomes).extracting(o -> o.entry().id()).containsOnly(outcomes.getFirst().entry().id());
        assertThat(outcomes).filteredOn(o -> !o.replayed()).hasSize(1);
        assertThat(balances(book)).containsEntry("bank:a", -100L);
    }

    @Test
    void randomConcurrentPostingsKeepTheBookBalancedAndCachedBalancesTrue() throws Exception {
        String book = newBook();
        List<String> pockets = List.of("bank:a", "bank:b", "cash:wallet", "bank:c");

        // 16 threads x 50 random transfers in both directions, on accounts that don't exist yet:
        // the first postings also race to create them.
        runConcurrently(16, () -> {
            var random = ThreadLocalRandom.current();
            for (int i = 0; i < 50; i++) {
                String from = pockets.get(random.nextInt(4));
                String to = pockets.get(random.nextInt(4));
                if (from.equals(to)) continue;
                long amount = random.nextLong(1, 10_000);
                post(book, "TRANSFER", OCT_10, leg(to, ASSET, amount), leg(from, ASSET, -amount));
            }
            return null;
        });

        Map<String, Long> balances = balances(book);
        assertThat(balances).hasSize(4);
        assertThat(balances.values().stream().mapToLong(Long::longValue).sum()).as("nothing created or destroyed").isZero();
        for (String pocket : pockets) {
            long sumOfPostings = jdbc.sql("""
                            select coalesce(sum(p.amount), 0) from postings p
                            join accounts a on a.id = p.account_id join books b on b.id = a.book_id
                            where b.ref = ? and a.ref = ?""")
                    .params(book, pocket).query(Long.class).single();
            assertThat(balances.get(pocket)).as("cached balance == sum of postings").isEqualTo(sumOfPostings);
        }
    }

    @Test
    void anAccountKeepsItsTypeAndABookKeepsItsKind() {
        String book = newBook();
        post(book, "EXPENSE", OCT_10, leg("category:food", EXPENSE, 100), leg("bank:a", ASSET, -100));

        assertThatThrownBy(() -> post(book, "INCOME", OCT_10, leg("bank:a", ASSET, 100), leg("category:food", INCOME, -100)))
                .isInstanceOf(RefConflictException.class).hasMessageContaining("is EXPENSE");
        assertThatThrownBy(() -> ledger.post(new PostingCommand(key(), book, BookKind.GROUP, "SPLIT", OCT_10, null, null,
                List.of(new Leg("member:x", ASSET, "INR", 1), new Leg("member:y", ASSET, "INR", -1)))))
                .isInstanceOf(RefConflictException.class).hasMessageContaining("PERSONAL book");
    }

    @Test
    void reversalCancelsAnEntryInItsOriginalPeriodExactlyOnce() {
        String book = newBook();
        LocalDate sep15 = LocalDate.of(2026, 9, 15);
        String original = key();
        postWithKey(original, book, sep15, leg("category:shopping", EXPENSE, 2_999_00), leg("card:icici", LIABILITY, -2_999_00));

        String reversalKey = key();
        PostingOutcome reversal = ledger.reverse(new ReversalCommand(reversalKey, original, "edited"));

        assertThat(reversal.entry().type()).isEqualTo("REVERSAL");
        assertThat(reversal.entry().effectiveDate()).as("dated like the original").isEqualTo(sep15);
        assertThat(balances(book)).containsEntry("category:shopping", 0L).containsEntry("card:icici", 0L);
        assertThat(queries.totals(book, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), EXPENSE, false))
                .as("September nets out").isEmpty();

        assertThat(ledger.reverse(new ReversalCommand(reversalKey, original, "edited")).replayed()).isTrue();
        assertThatThrownBy(() -> ledger.reverse(new ReversalCommand(key(), original, null)))
                .isInstanceOf(ReversalNotAllowedException.class).hasMessageContaining("already reversed");
        assertThatThrownBy(() -> ledger.reverse(new ReversalCommand(key(), reversalKey, null)))
                .isInstanceOf(ReversalNotAllowedException.class).hasMessageContaining("cannot be reversed");
    }

    @Test
    void totalsPerMonthFollowTheBusinessDateNotTheInsertTime() {
        String book = newBook();
        post(book, "EXPENSE", LocalDate.of(2026, 8, 31), leg("category:food", EXPENSE, 300), leg("bank:a", ASSET, -300));
        post(book, "EXPENSE", LocalDate.of(2026, 9, 1), leg("category:food", EXPENSE, 200), leg("bank:a", ASSET, -200));
        post(book, "EXPENSE", LocalDate.of(2026, 9, 20), leg("category:food", EXPENSE, 50), leg("bank:a", ASSET, -50));
        post(book, "EXPENSE", LocalDate.of(2026, 9, 20), leg("category:fuel", EXPENSE, 1_000), leg("bank:a", ASSET, -1_000));

        List<PeriodTotal> totals = queries.totals(book, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 30), EXPENSE, true);

        assertThat(totals).extracting(PeriodTotal::periodStart, PeriodTotal::accountRef, PeriodTotal::total)
                .containsExactly(
                        tuple(LocalDate.of(2026, 8, 1), "category:food", 300L),
                        tuple(LocalDate.of(2026, 9, 1), "category:food", 250L),
                        tuple(LocalDate.of(2026, 9, 1), "category:fuel", 1_000L));
    }

    @Test
    void databaseRefusesUnbalancedEntriesAndPostingsIntoAnotherBook() {
        String book = newBook();
        String other = newBook();
        post(book, "EXPENSE", OCT_10, leg("category:food", EXPENSE, 100), leg("bank:a", ASSET, -100));
        post(other, "EXPENSE", OCT_10, leg("category:food", EXPENSE, 100), leg("bank:a", ASSET, -100));
        UUID bookId = bookId(book);
        UUID food = accountId(book, "category:food");
        UUID foreignBank = accountId(other, "bank:a");

        assertThatThrownBy(() -> rawEntry(bookId, List.of(food), List.of(100L))).hasStackTraceContaining("unbalanced");
        assertThatThrownBy(() -> rawEntry(bookId, List.of(food, foreignBank), List.of(100L, -100L)))
                .hasStackTraceContaining("another book");
    }

    @Test
    void postingsAreAppendOnly() {
        String book = newBook();
        post(book, "EXPENSE", OCT_10, leg("category:food", EXPENSE, 100), leg("bank:a", ASSET, -100));

        assertThatThrownBy(() -> jdbc.sql("update postings set amount = 1000000 where account_id = ?")
                .param(accountId(book, "bank:a")).update())
                .hasStackTraceContaining("append-only");
    }

    @Test
    void preExistingWalletDataIsKeptInTheLegacyBook() {
        assertThat(jdbc.sql("""
                        select count(*) from accounts a join books b on b.id = a.book_id
                        where b.ref = 'legacy:wallet' and a.type = 'SYSTEM'""")
                .query(Long.class).single()).isEqualTo(4);
    }

    // ---------------------------------------------------------------- helpers

    private PostingOutcome post(String book, String type, LocalDate date, Leg... legs) {
        return ledger.post(new PostingCommand(key(), book, BookKind.PERSONAL, type, date, null, null, List.of(legs)));
    }

    private PostingOutcome postWithKey(String key, String book, Leg... legs) {
        return postWithKey(key, book, OCT_10, legs);
    }

    private PostingOutcome postWithKey(String key, String book, LocalDate date, Leg... legs) {
        return ledger.post(new PostingCommand(key, book, BookKind.PERSONAL, "EXPENSE", date, null, null, List.of(legs)));
    }

    private static Leg leg(String ref, AccountType type, long amount) {
        return new Leg(ref, type, "INR", amount);
    }

    private Map<String, Long> balances(String book) {
        return queries.balances(book, null).stream().collect(Collectors.toMap(Balance::accountRef, Balance::balance));
    }

    private void rawEntry(UUID bookId, List<UUID> accountIds, List<Long> amounts) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            UUID entryId = UUID.randomUUID();
            jdbc.sql("""
                            insert into journal_entries (id, book_id, type, idempotency_key, request_hash, effective_date)
                            values (?, ?, 'EXPENSE', ?, 'x', current_date)""")
                    .params(entryId, bookId, key()).update();
            for (int i = 0; i < accountIds.size(); i++) {
                jdbc.sql("insert into postings (journal_entry_id, account_id, amount, currency, balance_after) values (?, ?, ?, 'INR', 0)")
                        .params(entryId, accountIds.get(i), amounts.get(i)).update();
            }
        });
    }

    private UUID bookId(String book) {
        return jdbc.sql("select id from books where ref = ?").param(book).query(UUID.class).single();
    }

    private UUID accountId(String book, String ref) {
        return jdbc.sql("select a.id from accounts a join books b on b.id = a.book_id where b.ref = ? and a.ref = ?")
                .params(book, ref).query(UUID.class).single();
    }

    private static String newBook() {
        return "space:" + UUID.randomUUID();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(task));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        }
    }
}
