package dev.payledger.ledger.movement;

import dev.payledger.ledger.account.Account;
import dev.payledger.ledger.account.AccountRepository;
import dev.payledger.ledger.account.AccountType;
import dev.payledger.ledger.common.AccountNotFoundException;
import dev.payledger.ledger.common.InvalidPostingException;
import dev.payledger.ledger.common.UnsupportedCurrencyException;
import dev.payledger.ledger.journal.EntryType;
import dev.payledger.ledger.journal.LedgerService;
import dev.payledger.ledger.journal.PostingCommand;
import dev.payledger.ledger.journal.PostingCommand.Leg;
import dev.payledger.ledger.journal.PostingOutcome;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Turns business operations (deposit, transfer) into balanced ledger postings. */
@Service
public class MoneyMovementService {

    private final LedgerService ledger;
    private final AccountRepository accounts;

    public MoneyMovementService(LedgerService ledger, AccountRepository accounts) {
        this.ledger = ledger;
        this.accounts = accounts;
    }

    /** External money arrives: debit the funding account, credit the user's wallet. */
    public PostingOutcome deposit(String idempotencyKey, UUID accountId, long amount, String currency, String description) {
        Account funding = accounts.findByTypeAndCurrency(AccountType.SYSTEM, currency)
                .orElseThrow(() -> new UnsupportedCurrencyException(currency));
        requireUserAccount(accountId);

        return ledger.post(new PostingCommand(
                idempotencyKey,
                fingerprint("DEPOSIT", accountId, amount, currency, description),
                EntryType.DEPOSIT, currency, description,
                List.of(new Leg(funding.getId(), -amount), new Leg(accountId, amount))));
    }

    /** Wallet-to-wallet: debit the sender, credit the receiver, atomically. */
    public PostingOutcome transfer(String idempotencyKey, UUID fromAccountId, UUID toAccountId, long amount,
                                   String currency, String description) {
        if (fromAccountId.equals(toAccountId)) {
            throw new InvalidPostingException("cannot transfer to the same account");
        }
        requireUserAccount(fromAccountId);
        requireUserAccount(toAccountId);

        return ledger.post(new PostingCommand(
                idempotencyKey,
                fingerprint("TRANSFER", fromAccountId, toAccountId, amount, currency, description),
                EntryType.TRANSFER, currency, description,
                List.of(new Leg(fromAccountId, -amount), new Leg(toAccountId, amount))));
    }

    /** System accounts are internal; clients must never move money in or out of them directly. */
    private void requireUserAccount(UUID accountId) {
        Account account = accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
        if (account.getType() != AccountType.USER) {
            throw new AccountNotFoundException(accountId);
        }
    }

    static String fingerprint(Object... parts) {
        String canonical = String.join("|", Arrays.stream(parts).map(p -> Objects.toString(p, "")).toList());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed by the JDK", e);
        }
    }
}
