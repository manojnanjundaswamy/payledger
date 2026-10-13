package dev.payledger.core.money;

import dev.payledger.core.common.InvalidRequestException;
import dev.payledger.core.space.SpaceAccess;
import dev.payledger.core.user.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Transactions out, in the import template's columns (product plan 2.5: "template import and export"),
 * so a file can be opened in a spreadsheet, kept, or imported again. Each row's external_id is the
 * transaction's own reference, so importing an export back into the same space adds nothing.
 */
@Service
public class ExportService {

    private static final DateTimeFormatter DAY_FIRST = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private final JdbcClient jdbc;
    private final SpaceAccess access;

    public ExportService(JdbcClient jdbc, SpaceAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    /** One transaction in template order (see {@link ImportXlsx#COLUMNS}); the amount is in rupees, not paise. */
    record Line(LocalDate date, BigDecimal amount, String type, String account, String category, String subcategory,
                String description, String toAccount, String tags, String notes, String currency, String externalId) {

        List<String> cells() {
            return List.of(date.format(DAY_FIRST), amount.toPlainString(), type, account, category, subcategory, description,
                    "", "", toAccount, tags, notes, currency, externalId);
        }
    }

    @Transactional(readOnly = true)
    public List<Line> lines(CurrentUser user, UUID spaceId, LocalDate from, LocalDate to) {
        access.read(user, spaceId);
        if (from != null && to != null && to.isBefore(from)) {
            throw new InvalidRequestException("invalid-range", "to is before from");
        }
        return jdbc.sql("""
                        select t.id, t.external_id, t.occurred_on, t.amount, t.currency, t.kind, t.description, t.note,
                               array_to_string(t.tags, ';') as tags,
                               a.name as account, ta.name as to_account,
                               coalesce(p.name, c.name) as category, case when p.id is null then '' else c.name end as subcategory
                        from transactions t
                        join money_accounts a on a.id = t.account_id
                        left join money_accounts ta on ta.id = t.to_account_id
                        left join categories c on c.id = t.category_id
                        left join categories p on p.id = c.parent_id
                        where t.space_id = :spaceId and t.deleted_at is null
                          and (cast(:from as date) is null or t.occurred_on >= :from)
                          and (cast(:to as date) is null or t.occurred_on <= :to)
                        order by t.occurred_on, t.created_at
                        """)
                .param("spaceId", spaceId).param("from", from).param("to", to)
                .query((rs, n) -> new Line(
                        rs.getObject("occurred_on", LocalDate.class),
                        BigDecimal.valueOf(rs.getLong("amount"), 2),
                        rs.getString("kind").toLowerCase(),
                        rs.getString("account"),
                        nullToEmpty(rs.getString("category")),
                        nullToEmpty(rs.getString("subcategory")),
                        rs.getString("description"),
                        nullToEmpty(rs.getString("to_account")),
                        nullToEmpty(rs.getString("tags")),
                        nullToEmpty(rs.getString("note")),
                        rs.getString("currency"),
                        rs.getString("external_id") != null ? rs.getString("external_id") : rs.getObject("id", UUID.class).toString()))
                .list();
    }

    String csv(List<Line> lines) {
        StringBuilder out = new StringBuilder(String.join(",", ImportXlsx.COLUMNS)).append("\r\n");
        for (Line line : lines) {
            List<String> cells = new ArrayList<>();
            for (String cell : line.cells()) {
                cells.add(cell.contains(",") || cell.contains("\"") || cell.contains("\n") ? "\"" + cell.replace("\"", "\"\"") + "\"" : cell);
            }
            out.append(String.join(",", cells)).append("\r\n");
        }
        return out.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
