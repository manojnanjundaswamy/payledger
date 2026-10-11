package dev.payledger.ledger.journal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/** A single signed line in the ledger: positive = credit, negative = debit. Append-only. */
@Entity
@Immutable
@Table(name = "postings")
public class Posting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_entry_id")
    private JournalEntry journalEntry;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private String currency;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Posting() {
        // for JPA
    }

    Posting(JournalEntry journalEntry, UUID accountId, long amount, String currency, long balanceAfter, Instant createdAt) {
        this.journalEntry = journalEntry;
        this.accountId = accountId;
        this.amount = amount;
        this.currency = currency;
        this.balanceAfter = balanceAfter;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public JournalEntry getJournalEntry() { return journalEntry; }
    public UUID getAccountId() { return accountId; }
    public long getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public long getBalanceAfter() { return balanceAfter; }
    public Instant getCreatedAt() { return createdAt; }
}
