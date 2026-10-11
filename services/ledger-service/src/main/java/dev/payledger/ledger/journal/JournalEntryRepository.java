package dev.payledger.ledger.journal;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    @EntityGraph(attributePaths = "postings")
    Optional<JournalEntry> findByIdempotencyKey(String idempotencyKey);
}
