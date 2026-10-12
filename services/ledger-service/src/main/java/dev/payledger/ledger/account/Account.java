package dev.payledger.ledger.account;

import dev.payledger.ledger.common.InsufficientFundsException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    /** UUIDv7 is time-ordered, so inserts append to the B-tree index instead of splitting random pages. */
    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "book_id", nullable = false, updatable = false)
    private UUID bookId;

    /** The caller's name for this account inside the book, e.g. {@code category:<uuid>} or {@code bank:<uuid>}. */
    @Column(nullable = false, updatable = false)
    private String ref;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AccountType type;

    @Column(nullable = false, updatable = false)
    private String currency;

    /** Minor units (cents/paise). */
    @Column(nullable = false)
    private long balance;

    @Column(name = "allow_negative", nullable = false, updatable = false)
    private boolean allowNegative;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Account() {
        // for JPA
    }

    /**
     * Applies a signed posting amount (positive = debit) to the cached balance.
     * The caller must hold a row lock on this account (see {@link AccountRepository#lockAllByIdsOrdered}).
     */
    public void apply(long amount) {
        long newBalance = Math.addExact(balance, amount);
        if (newBalance < 0 && !allowNegative) {
            throw new InsufficientFundsException(id, balance, -amount);
        }
        balance = newBalance;
    }

    public UUID getId() { return id; }
    public UUID getBookId() { return bookId; }
    public String getRef() { return ref; }
    public AccountType getType() { return type; }
    public String getCurrency() { return currency; }
    public long getBalance() { return balance; }
    public Instant getCreatedAt() { return createdAt; }
}
