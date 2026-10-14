package dev.payledger.core.money;

import dev.payledger.core.common.ApiException;
import dev.payledger.core.common.InvalidRequestException;
import dev.payledger.core.common.NotFoundException;
import dev.payledger.core.space.SpaceAccess;
import dev.payledger.core.user.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Merging one category into another (product plan: categories "hide or merge"): its transactions move to
 * the other one, each as an ordinary edit (the ledger reverses the old entry and posts the new one, so
 * reports for past months move too), bills on the plan follow, its budget goes, and it is hidden. It can't
 * be deleted: deleted transactions and the ledger's history still name it.
 */
@Service
public class CategoryMergeService {

    private final JdbcClient jdbc;
    private final SpaceAccess access;
    private final CategoryService categories;
    private final TransactionService transactions;

    public CategoryMergeService(JdbcClient jdbc, SpaceAccess access, CategoryService categories, TransactionService transactions) {
        this.jdbc = jdbc;
        this.access = access;
        this.categories = categories;
        this.transactions = transactions;
    }

    public record Merged(UUID into, int moved) {
    }

    @Transactional
    public Merged merge(CurrentUser user, UUID spaceId, UUID sourceId, UUID targetId) {
        access.write(user, spaceId);
        if (sourceId.equals(targetId)) {
            throw new InvalidRequestException("invalid-merge", "a category can't be merged into itself");
        }
        // Both rows locked (in id order, so opposite merges can't deadlock): no transaction can be added to
        // the source while its transactions move, and the target can't be hidden or deleted meanwhile.
        for (UUID id : sourceId.compareTo(targetId) < 0 ? List.of(sourceId, targetId) : List.of(targetId, sourceId)) {
            lock(spaceId, id);
        }
        Category source = categories.find(spaceId, sourceId);
        Category target = categories.find(spaceId, targetId);
        if (!source.kind().equals(target.kind())) {
            throw new InvalidRequestException("invalid-merge", "both categories must be " + source.kind().toLowerCase());
        }
        if (target.hidden()) {
            throw new InvalidRequestException("invalid-merge", target.name() + " is hidden; show it first");
        }
        if (sourceId.equals(target.parentId())) {
            throw new InvalidRequestException("invalid-merge", target.name() + " is inside " + source.name());
        }
        if (jdbc.sql("select exists (select 1 from categories where parent_id = :id)").param("id", sourceId).query(Boolean.class).single()) {
            throw new HasSubcategoriesException();
        }

        record Live(UUID id) {
        }
        List<Live> live = jdbc.sql("select id from transactions where category_id = :id and deleted_at is null order by occurred_on")
                .param("id", sourceId).query(Live.class).list();
        for (Live l : live) {
            Transaction t = transactions.get(user, spaceId, l.id());
            transactions.replace(user, spaceId, t.id(), new TransactionRequests.Replace(t.version(), t.kind(), t.amount(),
                    t.accountId(), t.toAccountId(), targetId, t.occurredOn(), t.description(), t.note(), t.tags(), t.goalId()));
        }
        jdbc.sql("update category_rules set category_id = :target where category_id = :source")
                .param("target", targetId).param("source", sourceId).update();
        jdbc.sql("update commitments set category_id = :target, version = version + 1, updated_at = now() where category_id = :source")
                .param("target", targetId).param("source", sourceId).update();
        jdbc.sql("delete from budget_limits where category_id = :source").param("source", sourceId).update();
        jdbc.sql("update categories set hidden = true where id = :source").param("source", sourceId).update();
        return new Merged(targetId, live.size());
    }

    private void lock(UUID spaceId, UUID id) {
        jdbc.sql("select id from categories where id = :id and space_id = :spaceId for update")
                .param("id", id).param("spaceId", spaceId).query(UUID.class).optional()
                .orElseThrow(() -> new NotFoundException("category"));
    }

    static class HasSubcategoriesException extends ApiException {
        HasSubcategoriesException() {
            super(HttpStatus.CONFLICT, "category-has-subcategories", "merge or move its subcategories first");
        }
    }
}
