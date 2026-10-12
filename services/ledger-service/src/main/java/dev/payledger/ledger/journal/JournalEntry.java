package dev.payledger.ledger.journal;

import dev.payledger.ledger.account.Account;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** One business event (an expense, a split, a settlement...) made of two or more postings that balance. */
@Entity
@Immutable
@Table(name = "journal_entries")
public class JournalEntry {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "book_id", nullable = false)
    private UUID bookId;

    @Column(nullable = false)
    private String type;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    private String description;

    @Column(name = "source_ref")
    private String sourceRef;

    @Column(name = "reverses_entry_id")
    private UUID reversesEntryId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "journalEntry", cascade = CascadeType.PERSIST)
    @OrderBy("id")
    private List<Posting> postings = new ArrayList<>();

    protected JournalEntry() {
        // for JPA
    }

    JournalEntry(UUID bookId, String type, String idempotencyKey, String requestHash, LocalDate effectiveDate,
                 String description, String sourceRef, UUID reversesEntryId) {
        this.bookId = bookId;
        this.type = type;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.effectiveDate = effectiveDate;
        this.description = description;
        this.sourceRef = sourceRef;
        this.reversesEntryId = reversesEntryId;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS); // Postgres timestamptz precision
    }

    /** Applies the amount to the (already locked) account and records the posting. */
    void post(Account account, long amount) {
        account.apply(amount);
        postings.add(new Posting(this, account.getId(), amount, account.getCurrency(), account.getBalance(), createdAt));
    }

    public UUID getId() { return id; }
    public UUID getBookId() { return bookId; }
    public String getType() { return type; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
    public String getDescription() { return description; }
    public String getSourceRef() { return sourceRef; }
    public UUID getReversesEntryId() { return reversesEntryId; }
    public Instant getCreatedAt() { return createdAt; }
    public List<Posting> getPostings() { return Collections.unmodifiableList(postings); }
}
