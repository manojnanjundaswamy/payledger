package dev.payledger.ledger.book;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/** A set of accounts that balance together, identified by the caller's reference (e.g. {@code space:<uuid>}). */
@Entity
@Immutable
@Table(name = "books")
public class Book {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String ref;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookKind kind;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Book() {
        // for JPA
    }

    public UUID getId() { return id; }
    public String getRef() { return ref; }
    public BookKind getKind() { return kind; }
    public Instant getCreatedAt() { return createdAt; }
}
