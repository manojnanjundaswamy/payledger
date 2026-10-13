package dev.payledger.core.money;

import java.util.UUID;

/**
 * An expense or income category: in the ledger, an EXPENSE or INCOME account ({@code category:<id>}).
 *
 * @param icon  a lucide icon name, e.g. {@code shopping-basket}
 * @param color a chart series token, e.g. {@code series-3} (the web app maps it to its validated palette)
 */
public record Category(UUID id, String kind, String name, String icon, String color, UUID parentId, boolean hidden) {

    public String ledgerRef() {
        return "category:" + id;
    }
}
