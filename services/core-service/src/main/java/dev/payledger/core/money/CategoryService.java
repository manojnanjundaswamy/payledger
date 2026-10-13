package dev.payledger.core.money;

import dev.payledger.core.common.InvalidRequestException;
import dev.payledger.core.common.NotFoundException;
import dev.payledger.core.space.SpaceAccess;
import dev.payledger.core.user.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

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

    /** For transactions: the category must exist in this space and match the transaction's kind. */
    Category require(UUID spaceId, UUID categoryId, String kind) {
        Category category = jdbc.sql("select " + COLUMNS + " from categories where id = :id and space_id = :spaceId")
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
}
