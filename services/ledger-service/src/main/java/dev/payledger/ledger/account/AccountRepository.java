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
     * Every money movement locks its accounts through this one query, so all transactions
     * acquire row locks in the same (database-defined) order. Transfer A->B and B->A running
     * concurrently therefore queue up instead of deadlocking.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id in :ids order by a.id")
    List<Account> lockAllByIdsOrdered(@Param("ids") Collection<UUID> ids);

    Optional<Account> findByTypeAndCurrency(AccountType type, String currency);

    boolean existsByTypeAndCurrency(AccountType type, String currency);
}
