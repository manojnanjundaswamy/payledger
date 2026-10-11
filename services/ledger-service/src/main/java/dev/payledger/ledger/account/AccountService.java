package dev.payledger.ledger.account;

import dev.payledger.ledger.common.AccountNotFoundException;
import dev.payledger.ledger.common.UnsupportedCurrencyException;
import dev.payledger.ledger.journal.Posting;
import dev.payledger.ledger.journal.PostingRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final PostingRepository postings;

    public AccountService(AccountRepository accounts, PostingRepository postings) {
        this.accounts = accounts;
        this.postings = postings;
    }

    @Transactional
    public Account open(String ownerId, String currency) {
        // A currency is supported only if it has a funding account to deposit from.
        if (!accounts.existsByTypeAndCurrency(AccountType.SYSTEM, currency)) {
            throw new UnsupportedCurrencyException(currency);
        }
        return accounts.save(Account.openUserAccount(ownerId, currency));
    }

    @Transactional(readOnly = true)
    public Account get(UUID id) {
        return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    /** Newest first. Pass the last id you received as {@code beforeId} to get the next page. */
    @Transactional(readOnly = true)
    public List<Posting> statement(UUID accountId, Long beforeId, int limit) {
        get(accountId);
        return beforeId == null
                ? postings.findByAccountIdOrderByIdDesc(accountId, Limit.of(limit))
                : postings.findByAccountIdAndIdLessThanOrderByIdDesc(accountId, beforeId, Limit.of(limit));
    }
}
