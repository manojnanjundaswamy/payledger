package dev.payledger.ledger.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * SELECT ... ORDER BY id FOR UPDATE.
     * <p>
     * Every posting locks its accounts through this one query, so all transactions acquire row
     * locks in the same (database-defined) order. Two entries touching the same accounts in
     * opposite directions therefore queue up instead of deadlocking.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.bookId = :bookId and a.ref in :refs order by a.id")
    List<Account> lockAllByBookAndRefsOrdered(@Param("bookId") UUID bookId, @Param("refs") Collection<String> refs);

    /** Same ordering guarantee, for reversals that already know the account ids. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id in :ids order by a.id")
    List<Account> lockAllByIdsOrdered(@Param("ids") Collection<UUID> ids);

    Optional<Account> findByBookIdAndRefAndCurrency(UUID bookId, String ref, String currency);

    List<Account> findByBookIdOrderByRef(UUID bookId);
}
