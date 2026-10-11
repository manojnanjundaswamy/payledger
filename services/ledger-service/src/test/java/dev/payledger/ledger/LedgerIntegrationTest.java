package dev.payledger.ledger;

import dev.payledger.ledger.account.Account;
import dev.payledger.ledger.account.AccountRepository;
import dev.payledger.ledger.account.AccountService;
import dev.payledger.ledger.common.IdempotencyKeyReusedException;
import dev.payledger.ledger.common.InsufficientFundsException;
import dev.payledger.ledger.journal.JournalEntryRepository;
import dev.payledger.ledger.journal.PostingOutcome;
import dev.payledger.ledger.journal.PostingRepository;
import dev.payledger.ledger.movement.MoneyMovementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LedgerIntegrationTest {

    @Autowired AccountService accountService;
    @Autowired MoneyMovementService movements;
    @Autowired AccountRepository accounts;
    @Autowired JournalEntryRepository entries;
    @Autowired PostingRepository postings;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void depositThenTransferMovesMoneyAndRecordsBalancedPostings() {
        Account alice = open("alice");
        Account bob = open("bob");

        movements.deposit(key(), alice.getId(), 10_000, "USD", "top-up");
        PostingOutcome transfer = movements.transfer(key(), alice.getId(), bob.getId(), 2_500, "USD", "dinner");

        assertThat(balance(alice)).isEqualTo(7_500);
        assertThat(balance(bob)).isEqualTo(2_500);
        assertThat(transfer.entry().postings()).hasSize(2)
                .extracting(p -> p.amount()).containsExactlyInAnyOrder(-2_500L, 2_500L);
    }

    @Test
    void transferBeyondBalanceIsRejectedAndLeavesNoTrace() {
        Account alice = open("alice");
        Account bob = open("bob");
        movements.deposit(key(), alice.getId(), 1_000, "USD", null);
        long entriesBefore = entries.count();

        assertThatThrownBy(() -> movements.transfer(key(), alice.getId(), bob.getId(), 1_001, "USD", null))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(balance(alice)).isEqualTo(1_000);
        assertThat(balance(bob)).isZero();
        assertThat(entries.count()).isEqualTo(entriesBefore);
    }

    @Test
    void retryWithSameIdempotencyKeyReplaysInsteadOfPayingTwice() {
        Account alice = open("alice");
        Account bob = open("bob");
        movements.deposit(key(), alice.getId(), 5_000, "USD", null);
        String key = key();

        PostingOutcome first = movements.transfer(key, alice.getId(), bob.getId(), 1_000, "USD", "rent");
        PostingOutcome retry = movements.transfer(key, alice.getId(), bob.getId(), 1_000, "USD", "rent");

        assertThat(first.replayed()).isFalse();
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.entry().id()).isEqualTo(first.entry().id());
        assertThat(balance(bob)).isEqualTo(1_000);
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejected() {
        Account alice = open("alice");
        Account bob = open("bob");
        movements.deposit(key(), alice.getId(), 5_000, "USD", null);
        String key = key();
        movements.transfer(key, alice.getId(), bob.getId(), 1_000, "USD", null);

        assertThatThrownBy(() -> movements.transfer(key, alice.getId(), bob.getId(), 999, "USD", null))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void concurrentDuplicateRequestsMoveMoneyExactlyOnce() throws Exception {
        Account alice = open("alice");
        Account bob = open("bob");
        movements.deposit(key(), alice.getId(), 10_000, "USD", null);
        String key = key();

        List<PostingOutcome> outcomes = runConcurrently(20,
                () -> movements.transfer(key, alice.getId(), bob.getId(), 100, "USD", null));

        assertThat(outcomes).extracting(o -> o.entry().id()).containsOnly(outcomes.getFirst().entry().id());
        assertThat(outcomes).filteredOn(o -> !o.replayed()).hasSize(1);
        assertThat(balance(bob)).isEqualTo(100);
    }

    @Test
    void randomConcurrentTransfersConserveMoneyAndNeverOverdraw() throws Exception {
        List<Account> wallets = List.of(open("w1"), open("w2"), open("w3"), open("w4"));
        for (Account w : wallets) {
            movements.deposit(key(), w.getId(), 10_000, "USD", null);
        }

        // 16 threads x 50 random transfers between the 4 wallets, both directions, in parallel.
        runConcurrently(16, () -> {
            var random = ThreadLocalRandom.current();
            for (int i = 0; i < 50; i++) {
                Account from = wallets.get(random.nextInt(4));
                Account to = wallets.get(random.nextInt(4));
                if (from == to) continue;
                try {
                    movements.transfer(key(), from.getId(), to.getId(), random.nextLong(1, 800), "USD", null);
                } catch (InsufficientFundsException expected) {
                    // fine: overdraft correctly refused
                }
            }
            return null;
        });

        long total = 0;
        for (Account w : wallets) {
            long cached = balance(w);
            assertThat(cached).as("never negative").isNotNegative();
            assertThat(cached).as("cached balance == sum of postings").isEqualTo(postings.sumAmountsForAccount(w.getId()));
            total += cached;
        }
        assertThat(total).as("money is neither created nor destroyed").isEqualTo(40_000);
    }

    @Test
    void databaseRefusesToCommitAnUnbalancedEntry() {
        Account alice = open("alice");
        var tx = new TransactionTemplate(txManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            UUID entryId = UUID.randomUUID();
            jdbc.sql("insert into journal_entries (id, type, idempotency_key, request_hash) values (?, 'DEPOSIT', ?, 'x')")
                    .params(entryId, key()).update();
            jdbc.sql("insert into postings (journal_entry_id, account_id, amount, currency, balance_after) values (?, ?, 100, 'USD', 100)")
                    .params(entryId, alice.getId()).update();
        })).hasStackTraceContaining("unbalanced");
    }

    @Test
    void postingsAreAppendOnly() {
        Account alice = open("alice");
        movements.deposit(key(), alice.getId(), 1_000, "USD", null);

        assertThatThrownBy(() -> jdbc.sql("update postings set amount = 1000000 where account_id = ?")
                .param(alice.getId()).update())
                .hasStackTraceContaining("append-only");
    }

    private Account open(String owner) {
        return accountService.open(owner, "USD");
    }

    private long balance(Account account) {
        return accounts.findById(account.getId()).orElseThrow().getBalance();
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
