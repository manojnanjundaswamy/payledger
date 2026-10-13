package dev.payledger.core.money;

import dev.payledger.core.common.ApiException;
import dev.payledger.core.common.InvalidRequestException;
import dev.payledger.core.common.NotFoundException;
import dev.payledger.core.common.Uuid7;
import dev.payledger.core.space.SpaceAccess;
import dev.payledger.core.user.CurrentUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Categories: the defaults every space starts with, plus the user's own. Subcategories go one level
 * deep. A category that was ever used keeps its history: it can be hidden but not deleted.
 */
@Service
public class CategoryService {

    private static final String COLUMNS = "id, kind, name, icon, color, parent_id, hidden";

    private final JdbcClient jdbc;
    private final SpaceAccess access;

    public CategoryService(JdbcClient jdbc, SpaceAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    /** Gives a new space the default categories (the list lives in the default_categories table). */
    public void seedDefaults(UUID spaceId) {
        jdbc.sql("""
                        insert into categories (id, space_id, kind, name, icon, color, sort_order, seed_slug)
                        select gen_random_uuid(), :spaceId, kind, name, icon, color, sort_order, slug
                        from default_categories
                        on conflict (space_id, seed_slug) do nothing
                        """)
                .param("spaceId", spaceId)
                .update();
    }

    @Transactional(readOnly = true)
    public List<Category> list(CurrentUser user, UUID spaceId) {
        access.read(user, spaceId);
        return jdbc.sql("select " + COLUMNS + " from categories where space_id = :spaceId order by kind, sort_order, name")
                .param("spaceId", spaceId)
                .query(Category.class)
                .list();
    }

    @Transactional
    public Category create(CurrentUser user, UUID spaceId, CategoryRequests.Create request) {
        access.write(user, spaceId);
        String name = request.name().strip();
        if (request.parentId() != null) {
            checkParent(spaceId, null, request.kind(), request.parentId(), false);
        }
        UUID id = Uuid7.next();
        // No conflict target: the only one that can fire is the case-insensitive name index (V3).
        // Checking for the name first would let two concurrent creates of "Pets" both pass.
        int inserted = jdbc.sql("""
                        insert into categories (id, space_id, kind, name, icon, color, parent_id)
                        values (:id, :spaceId, :kind, :name, :icon, :color, :parentId)
                        on conflict do nothing
                        """)
                .param("id", id).param("spaceId", spaceId).param("kind", request.kind()).param("name", name)
                .param("icon", request.icon()).param("color", request.color()).param("parentId", request.parentId())
                .update();
        if (inserted == 0) {
            throw new NameInUseException(name);
        }
        return find(spaceId, id);
    }

    @Transactional
    public Category replace(CurrentUser user, UUID spaceId, UUID id, CategoryRequests.Replace request) {
        access.write(user, spaceId);
        Category before = find(spaceId, id);
        if (request.parentId() != null) {
            checkParent(spaceId, id, before.kind(), request.parentId(), request.hidden());
        }
        String name = request.name().strip();
        try {
            jdbc.sql("""
                            update categories set name = :name, icon = :icon, color = :color, parent_id = :parentId, hidden = :hidden
                            where id = :id
                            """)
                    .param("id", id).param("name", name).param("icon", request.icon()).param("color", request.color())
                    .param("parentId", request.parentId()).param("hidden", request.hidden())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new NameInUseException(name);
        }
        if (request.hidden() != before.hidden()) {
            // Subcategories follow their parent, so hiding "Food" doesn't leave "Food > Snacks" on offer.
            jdbc.sql("update categories set hidden = :hidden where parent_id = :id")
                    .param("id", id).param("hidden", request.hidden())
                    .update();
        }
        return find(spaceId, id);
    }

    /** Only for a category nothing ever used (a deleted transaction counts too: the ledger keeps its entries). */
    @Transactional
    public void delete(CurrentUser user, UUID spaceId, UUID id) {
        access.write(user, spaceId);
        // The row lock makes a concurrent new transaction (which takes a share lock in require) wait
        // for this delete and then fail with category-not-found, or this check see its row.
        jdbc.sql("select id from categories where id = :id and space_id = :spaceId for update")
                .param("id", id).param("spaceId", spaceId)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new NotFoundException("category"));
        if (exists("select 1 from transactions where category_id = :id", id)) {
            throw new InUseException("category-in-use", "it has transactions; hide it instead, and its history stays in your reports");
        }
        if (exists("select 1 from categories where parent_id = :id", id)) {
            throw new InUseException("category-has-subcategories", "it has subcategories; move or delete them first");
        }
        // An unused category's budget is only a setting, so it goes with it.
        jdbc.sql("delete from budget_limits where category_id = :id").param("id", id).update();
        jdbc.sql("delete from categories where id = :id").param("id", id).update();
    }

    /** For transactions: the category must exist in this space and match the transaction's kind. */
    Category require(UUID spaceId, UUID categoryId, String kind) {
        Category category = jdbc.sql("select " + COLUMNS + " from categories where id = :id and space_id = :spaceId for share")
                .param("id", categoryId).param("spaceId", spaceId)
                .query(Category.class)
                .optional()
                .orElseThrow(() -> new NotFoundException("category"));
        if (!category.kind().equals(kind)) {
            throw new InvalidRequestException("category-kind-mismatch",
                    "category " + category.name() + " is for " + category.kind().toLowerCase() + ", not " + kind.toLowerCase());
        }
        return category;
    }

    private Category find(UUID spaceId, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from categories where id = :id and space_id = :spaceId")
                .param("id", id).param("spaceId", spaceId)
                .query(Category.class)
                .optional()
                .orElseThrow(() -> new NotFoundException("category"));
    }

    private boolean exists(String query, UUID id) {
        return jdbc.sql("select exists (" + query + ")").param("id", id).query(Boolean.class).single();
    }

    /** One level of subcategories, of the same kind, and none shown under a hidden parent. */
    private void checkParent(UUID spaceId, UUID childId, String kind, UUID parentId, boolean childHidden) {
        if (parentId.equals(childId)) {
            throw invalidParent("a category can't be its own parent");
        }
        Category parent = jdbc.sql("select " + COLUMNS + " from categories where id = :id and space_id = :spaceId")
                .param("id", parentId).param("spaceId", spaceId)
                .query(Category.class)
                .optional()
                .orElseThrow(() -> invalidParent("no such parent category"));
        if (!parent.kind().equals(kind)) {
            throw invalidParent(parent.name() + " is an " + parent.kind().toLowerCase() + " category");
        }
        if (parent.parentId() != null) {
            throw invalidParent(parent.name() + " is itself a subcategory; subcategories go one level deep");
        }
        if (childId != null && exists("select 1 from categories where parent_id = :id", childId)) {
            throw invalidParent("this category has subcategories of its own");
        }
        if (parent.hidden() && !childHidden) {
            throw invalidParent(parent.name() + " is hidden; show it first");
        }
    }

    private static InvalidRequestException invalidParent(String message) {
        return new InvalidRequestException("category-parent-invalid", message);
    }

    static class NameInUseException extends ApiException {
        NameInUseException(String name) {
            super(HttpStatus.CONFLICT, "category-name-in-use", "there is already a category called " + name);
        }
    }

    static class InUseException extends ApiException {
        InUseException(String code, String why) {
            super(HttpStatus.CONFLICT, code, "this category can't be deleted: " + why);
        }
    }
}
