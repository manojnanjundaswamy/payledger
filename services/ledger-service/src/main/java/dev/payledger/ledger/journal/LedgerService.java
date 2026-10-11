package dev.payledger.ledger.journal;

import dev.payledger.ledger.account.Account;
import dev.payledger.ledger.account.AccountRepository;
import dev.payledger.ledger.common.AccountNotFoundException;
import dev.payledger.ledger.common.CurrencyMismatchException;
import dev.payledger.ledger.common.IdempotencyKeyReusedException;
import dev.payledger.ledger.common.InvalidPostingException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The only component allowed to write to the ledger.
 * <p>
 * One call = one database transaction that:
 * <ol>
 *   <li>returns the stored result if the idempotency key was already used,</li>
 *   <li>locks every involved account row in a consistent order,</li>
 *   <li>applies the postings and updates cached balances,</li>
 *   <li>inserts the entry; the UNIQUE(idempotency_key) constraint catches concurrent duplicates.</li>
 * </ol>
 * Transactions are demarcated with {@link TransactionTemplate} rather than {@code @Transactional}
 * because the duplicate-key recovery needs a second, separate transaction after the first one
 * rolled back. That is awkward with proxies and self-invocation.
 */
@Service
public class LedgerService {

    private final AccountRepository accounts;
    private final JournalEntryRepository entries;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnlyTx;

    public LedgerService(AccountRepository accounts, JournalEntryRepository entries, PlatformTransactionManager txManager) {
        this.accounts = accounts;
        this.entries = entries;
        this.tx = new TransactionTemplate(txManager);
        this.readOnlyTx = new TransactionTemplate(txManager);
        this.readOnlyTx.setReadOnly(true);
    }

    public PostingOutcome post(PostingCommand command) {
        validate(command);
        try {
            return tx.execute(status -> postInTransaction(command));
        } catch (DataIntegrityViolationException e) {
            // Two requests with the same key raced. Both passed the "already exists?" check, and the
            // loser hit the unique constraint. Its transaction is rolled back, so no money moved.
            // Return what the winner stored.
            return readOnlyTx.execute(status -> findExisting(command)).orElseThrow(() -> e);
        }
    }

    private PostingOutcome postInTransaction(PostingCommand command) {
        Optional<PostingOutcome> existing = findExisting(command);
        if (existing.isPresent()) {
            return existing.get();
        }

        Map<UUID, Account> locked = accounts
                .lockAllByIdsOrdered(command.legs().stream().map(PostingCommand.Leg::accountId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));

        var entry = new JournalEntry(command.type(), command.idempotencyKey(), command.requestHash(), command.description());
        for (PostingCommand.Leg leg : command.legs()) {
            Account account = locked.get(leg.accountId());
            if (account == null) {
                throw new AccountNotFoundException(leg.accountId());
            }
            if (!account.getCurrency().equals(command.currency())) {
                throw new CurrencyMismatchException(account.getId(), account.getCurrency(), command.currency());
            }
            entry.post(account, leg.amount());
        }

        entries.saveAndFlush(entry);
        return new PostingOutcome(JournalEntryView.of(entry), false);
    }

    private Optional<PostingOutcome> findExisting(PostingCommand command) {
        return entries.findByIdempotencyKey(command.idempotencyKey()).map(entry -> {
            if (!entry.getRequestHash().equals(command.requestHash())) {
                throw new IdempotencyKeyReusedException(command.idempotencyKey());
            }
            return new PostingOutcome(JournalEntryView.of(entry), true);
        });
    }

    private static void validate(PostingCommand command) {
        if (command.legs().size() < 2) {
            throw new InvalidPostingException("a journal entry needs at least two postings");
        }
        long sum = 0;
        for (PostingCommand.Leg leg : command.legs()) {
            if (leg.amount() == 0) {
                throw new InvalidPostingException("posting amounts must be non-zero");
            }
            sum = Math.addExact(sum, leg.amount());
        }
        if (sum != 0) {
            throw new InvalidPostingException("postings must sum to zero but sum to " + sum);
        }
    }
}
