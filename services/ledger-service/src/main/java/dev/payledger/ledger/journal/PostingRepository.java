package dev.payledger.ledger.journal;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Statements use keyset ("cursor") pagination: WHERE id < :cursor ORDER BY id DESC LIMIT n.
 * Unlike OFFSET, the cost stays constant on page 1 or page 10,000, and rows inserted while a
 * client is paging never cause duplicates or gaps.
 */
public interface PostingRepository extends JpaRepository<Posting, Long> {

    List<Posting> findByAccountIdOrderByIdDesc(UUID accountId, Limit limit);

    List<Posting> findByAccountIdAndIdLessThanOrderByIdDesc(UUID accountId, long beforeId, Limit limit);

    @Query("select coalesce(sum(p.amount), 0) from Posting p where p.accountId = :accountId")
    long sumAmountsForAccount(@Param("accountId") UUID accountId);
}
