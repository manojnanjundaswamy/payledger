package dev.payledger.core.money;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

final class CategoryRequests {

    /** Lucide icon names (the web app falls back to a plain circle for one it doesn't know). */
    static final String ICON = "[a-z0-9]+(-[a-z0-9]+)*";
    /** The validated chart palette: series-1 to series-8. */
    static final String COLOR = "series-[1-8]";

    private CategoryRequests() {
    }

    /** @param parentId makes it a subcategory (one level deep, same kind as the parent) */
    record Create(
            @NotNull @Pattern(regexp = "EXPENSE|INCOME") String kind,
            @NotBlank @Size(max = 40) String name,
            @NotNull @Size(max = 32) @Pattern(regexp = ICON) String icon,
            @NotNull @Pattern(regexp = COLOR) String color,
            UUID parentId) {
    }

    /**
     * The whole category, as the client wants it (kind can't change: the ledger account keeps its type).
     * Hiding a category also hides its subcategories, and showing it shows them again.
     */
    record Replace(
            @NotBlank @Size(max = 40) String name,
            @NotNull @Size(max = 32) @Pattern(regexp = ICON) String icon,
            @NotNull @Pattern(regexp = COLOR) String color,
            UUID parentId,
            boolean hidden) {
    }
}
