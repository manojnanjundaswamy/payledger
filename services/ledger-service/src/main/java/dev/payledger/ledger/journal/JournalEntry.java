package dev.payledger.ledger.journal;

import dev.payledger.ledger.account.Account;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** One business event (a deposit, a transfer...) made of two or more postings that sum to zero. */
@Entity
@Immutable
@Table(name = "journal_entries")
public class JournalEntry {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntryType type;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "journalEntry", cascade = CascadeType.PERSIST)
    @OrderBy("id")
    private List<Posting> postings = new ArrayList<>();

    protected JournalEntry() {
        // for JPA
    }

    JournalEntry(EntryType type, String idempotencyKey, String requestHash, String description) {
        this.type = type;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.description = description;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS); // Postgres timestamptz precision
    }

    /** Applies the amount to the (already locked) account and records the posting. */
    void post(Account account, long amount) {
        account.apply(amount);
        postings.add(new Posting(this, account.getId(), amount, account.getCurrency(), account.getBalance(), createdAt));
    }

    public UUID getId() { return id; }
    public EntryType getType() { return type; }
    public String getRequestHash() { return requestHash; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
    public List<Posting> getPostings() { return Collections.unmodifiableList(postings); }
}
