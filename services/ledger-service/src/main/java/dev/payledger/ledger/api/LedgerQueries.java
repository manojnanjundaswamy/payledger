package dev.payledger.ledger.api;

import dev.payledger.ledger.account.Account;
import dev.payledger.ledger.account.AccountRepository;
import dev.payledger.ledger.account.AccountType;
import dev.payledger.ledger.book.Book;
import dev.payledger.ledger.book.BookRepository;
import dev.payledger.ledger.common.AccountNotFoundException;
import dev.payledger.ledger.common.BookNotFoundException;
import dev.payledger.ledger.journal.Posting;
import dev.payledger.ledger.journal.PostingRepository;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Read side: balances, period totals for budgets and reports, and statements. */
@Service
public class LedgerQueries {

    private final BookRepository books;
    private final AccountRepository accounts;
    private final PostingRepository postings;
    private final JdbcClient jdbc;

    public LedgerQueries(BookRepository books, AccountRepository accounts, PostingRepository postings, JdbcClient jdbc) {
        this.books = books;
        this.accounts = accounts;
        this.postings = postings;
        this.jdbc = jdbc;
    }

    /** Current balance of every account in the book (optionally only some refs). */
    @Transactional(readOnly = true)
    public List<Balance> balances(String bookRef, Collection<String> accountRefs) {
        Book book = book(bookRef);
        return accounts.findByBookIdOrderByRef(book.getId()).stream()
                .filter(a -> accountRefs == null || accountRefs.isEmpty() || accountRefs.contains(a.getRef()))
                .map(a -> new Balance(a.getRef(), a.getType(), a.getCurrency(), a.getBalance()))
                .toList();
    }

    /**
     * Net movement per account between two business dates (inclusive), optionally per calendar month.
     * Answers "how much did I spend per category in October?" and "monthly trend for the last 6 months".
     * Reversals carry the original's date, so an edited or deleted transaction nets out of the right period.
     */
    @Transactional(readOnly = true)
    public List<PeriodTotal> totals(String bookRef, LocalDate from, LocalDate to, AccountType type, boolean byMonth) {
        Book book = book(bookRef);
        return jdbc.sql("""
                        select a.ref as account_ref, a.type as account_type, a.currency,
                               case when :byMonth then date_trunc('month', e.effective_date)::date end as period_start,
                               sum(p.amount) as total
                        from journal_entries e
                        join postings p on p.journal_entry_id = e.id
                        join accounts a on a.id = p.account_id
                        where e.book_id = :bookId
                          and e.effective_date between :from and :to
                          and (cast(:type as varchar) is null or a.type = :type)
                        group by a.ref, a.type, a.currency, period_start
                        having sum(p.amount) <> 0
                        order by period_start nulls first, a.ref, a.currency
                        """)
                .param("byMonth", byMonth)
                .param("bookId", book.getId())
                .param("from", from)
                .param("to", to)
                .param("type", type == null ? null : type.name())
                .query(PeriodTotal.class)
                .list();
    }

    /** Postings of one account, newest first, keyset-paginated (pass the last id as {@code beforeId}). */
    @Transactional(readOnly = true)
    public List<StatementLine> statement(String bookRef, String accountRef, String currency, Long beforeId, int limit) {
        Book book = book(bookRef);
        Account account = accounts.findByBookIdAndRefAndCurrency(book.getId(), accountRef, currency)
                .orElseThrow(() -> new AccountNotFoundException(bookRef, accountRef, currency));
        List<Posting> page = beforeId == null
                ? postings.findByAccountIdOrderByIdDesc(account.getId(), Limit.of(limit))
                : postings.findByAccountIdAndIdLessThanOrderByIdDesc(account.getId(), beforeId, Limit.of(limit));
        return page.stream()
                .map(p -> new StatementLine(p.getId(), p.getJournalEntry().getId(), p.getJournalEntry().getType(),
                        p.getJournalEntry().getEffectiveDate(), p.getJournalEntry().getDescription(),
                        p.getAmount(), p.getBalanceAfter()))
                .toList();
    }

    private Book book(String ref) {
        return books.findByRef(ref).orElseThrow(() -> new BookNotFoundException(ref));
    }

    public record Balance(String accountRef, AccountType accountType, String currency, long balance) {
    }

    /** @param periodStart first day of the month, or null when not grouped by month */
    public record PeriodTotal(String accountRef, AccountType accountType, String currency, LocalDate periodStart, long total) {
    }

    public record StatementLine(long postingId, UUID entryId, String entryType, LocalDate effectiveDate,
                                String description, long amount, long balanceAfter) {
    }
}
