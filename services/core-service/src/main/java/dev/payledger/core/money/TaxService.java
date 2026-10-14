package dev.payledger.core.money;

import dev.payledger.core.common.InvalidRequestException;
import dev.payledger.core.space.SpaceAccess;
import dev.payledger.core.user.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Indian income-tax deductions for the April–March financial year (old regime): what you paid towards each section,
 * against its limit, with the entries behind it, ready to hand to a CA. A transaction counts towards a section when
 * it's tagged with the section's code ("80C"), else when its category (or the category's parent) is mapped to one.
 */
@Service
public class TaxService {

    /** @param limit paise; null where the law sets no fixed cap */
    public record Section(String code, String name, Long limit, String covers) {
    }

    /** Amounts for FY 2025-26 onwards. Only the old regime has these; the new regime allows almost none (80CCD(2) aside). */
    public static final List<Section> SECTIONS = List.of(
            new Section("80C", "80C", 15_000_000L, "PPF, ELSS, EPF, life insurance, tuition fees, home-loan principal, Sukanya Samriddhi"),
            new Section("80CCD1B", "80CCD(1B)", 5_000_000L, "Your own NPS contributions, over 80C"),
            new Section("80D", "80D", 2_500_000L, "Health insurance and check-ups for you, your spouse and children"),
            new Section("80D-P", "80D (parents)", 5_000_000L, "Health insurance for parents (₹50,000 if they're seniors)"),
            new Section("24B", "24(b)", 20_000_000L, "Home-loan interest on a house you live in"),
            new Section("80E", "80E", null, "Interest on an education loan"),
            new Section("80G", "80G", null, "Donations to approved charities (50% or 100%)"),
            new Section("HRA", "HRA", null, "Rent you pay, for the HRA exemption from your salary"));

    private final JdbcClient jdbc;
    private final SpaceAccess access;
    private final AccountService accounts;

    TaxService(JdbcClient jdbc, SpaceAccess access, AccountService accounts) {
        this.jdbc = jdbc;
        this.access = access;
        this.accounts = accounts;
    }

    public record Item(UUID id, String description, long amount, LocalDate occurredOn, UUID categoryId, boolean tagged) {
    }

    /** @param room what's still claimable under the limit, or null without one */
    public record SectionTotal(String code, String name, Long limit, String covers, long claimed, Long room, List<Item> items) {
    }

    public record Mapping(UUID categoryId, String section) {
    }

    /** @param suggested categories whose names look like a section, not mapped yet */
    public record Report(int fy, LocalDate from, LocalDate to, List<SectionTotal> sections, List<Mapping> mappings, List<Mapping> suggested) {
    }

    /** @param fy the year the financial year starts in (2026: April 2026 to March 2027); null: the current one */
    @Transactional(readOnly = true)
    public Report report(CurrentUser user, UUID spaceId, Integer fy) {
        access.read(user, spaceId);
        LocalDate today = accounts.today(user);
        int year = fy != null ? fy : (today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1);
        if (year < 2000 || year > 2100) {
            throw new InvalidRequestException("invalid-fy", "choose a financial year like 2026");
        }
        LocalDate from = LocalDate.of(year, 4, 1);
        LocalDate to = LocalDate.of(year + 1, 3, 31);
        List<Mapping> mappings = mappings(spaceId);
        record Row(UUID id, String description, long amount, LocalDate occurredOn, UUID categoryId, String section, boolean tagged) {
        }
        // A section tag wins over the category's section; each transaction counts once.
        List<Row> rows = jdbc.sql("""
                        select t.id, t.description, t.amount, t.occurred_on, t.category_id,
                               coalesce(tag.section, m.section, pm.section) as section, tag.section is not null as tagged
                        from transactions t
                        left join categories c on c.id = t.category_id
                        left join tax_mappings m on m.space_id = t.space_id and m.category_id = t.category_id
                        left join tax_mappings pm on pm.space_id = t.space_id and pm.category_id = c.parent_id
                        left join lateral (select upper(x) as section from unnest(t.tags) x where upper(x) in (:codes) limit 1) tag on true
                        where t.space_id = :spaceId and t.deleted_at is null and t.occurred_on between :from and :to
                          and (t.kind = 'EXPENSE' or (t.kind = 'TRANSFER' and tag.section is not null))
                          and coalesce(tag.section, m.section, pm.section) is not null
                        order by t.occurred_on, t.created_at
                        """)
                .param("spaceId", spaceId).param("from", from).param("to", to)
                .param("codes", SECTIONS.stream().map(Section::code).toList())
                .query(Row.class).list();
        List<SectionTotal> totals = new ArrayList<>();
        for (Section s : SECTIONS) {
            List<Item> items = rows.stream().filter(r -> r.section().equals(s.code()))
                    .map(r -> new Item(r.id(), r.description(), r.amount(), r.occurredOn(), r.categoryId(), r.tagged())).toList();
            long claimed = items.stream().mapToLong(Item::amount).sum();
            totals.add(new SectionTotal(s.code(), s.name(), s.limit(), s.covers(), claimed,
                    s.limit() == null ? null : Math.max(0, s.limit() - claimed), items));
        }
        return new Report(year, from, to, totals, mappings, suggestions(spaceId, mappings));
    }

    @Transactional(readOnly = true)
    public List<Mapping> mappings(CurrentUser user, UUID spaceId) {
        access.read(user, spaceId);
        return mappings(spaceId);
    }

    /** Maps a category to a section, or (section null) takes it off. */
    @Transactional
    public List<Mapping> map(CurrentUser user, UUID spaceId, UUID categoryId, String section) {
        access.write(user, spaceId);
        boolean mine = jdbc.sql("select exists (select 1 from categories where id = :c and space_id = :s and kind = 'EXPENSE')")
                .param("c", categoryId).param("s", spaceId).query(Boolean.class).single();
        if (!mine) {
            throw new InvalidRequestException("invalid-category", "choose one of your spending categories");
        }
        if (section == null || section.isBlank()) {
            jdbc.sql("delete from tax_mappings where space_id = :s and category_id = :c").param("s", spaceId).param("c", categoryId).update();
        } else {
            String code = section.strip().toUpperCase(Locale.ROOT);
            if (SECTIONS.stream().noneMatch(s -> s.code().equals(code))) {
                throw new InvalidRequestException("invalid-section", "unknown tax section " + section);
            }
            jdbc.sql("""
                            insert into tax_mappings (space_id, category_id, section) values (:s, :c, :section)
                            on conflict (space_id, category_id) do update set section = excluded.section
                            """).param("s", spaceId).param("c", categoryId).param("section", code).update();
        }
        return mappings(spaceId);
    }

    /** The report as CSV, one line per entry, for a CA. */
    @Transactional(readOnly = true)
    public String csv(CurrentUser user, UUID spaceId, Integer fy) {
        Report r = report(user, spaceId, fy);
        Map<UUID, String> names = new LinkedHashMap<>();
        jdbc.sql("select id, name from categories where space_id = :s").param("s", spaceId)
                .query((rs, n) -> names.put(rs.getObject("id", UUID.class), rs.getString("name"))).list();
        StringBuilder out = new StringBuilder("section,date,description,category,amount\n");
        for (SectionTotal s : r.sections()) {
            for (Item i : s.items()) {
                out.append(s.name()).append(',').append(i.occurredOn()).append(',').append(cell(i.description())).append(',')
                        .append(cell(names.getOrDefault(i.categoryId(), ""))).append(',').append(rupees(i.amount())).append('\n');
            }
            if (!s.items().isEmpty()) {
                out.append(s.name()).append(",,Total,,").append(rupees(s.claimed())).append('\n');
            }
        }
        return out.toString();
    }

    private List<Mapping> mappings(UUID spaceId) {
        return jdbc.sql("select category_id, section from tax_mappings where space_id = :s").param("s", spaceId).query(Mapping.class).list();
    }

    /** Categories named like a section ("Rent", "Health insurance", "Donations"), not mapped yet. */
    private List<Mapping> suggestions(UUID spaceId, List<Mapping> mapped) {
        record Cat(UUID id, String name) {
        }
        List<Cat> cats = jdbc.sql("select id, name from categories where space_id = :s and kind = 'EXPENSE' and not hidden")
                .param("s", spaceId).query(Cat.class).list();
        List<Mapping> out = new ArrayList<>();
        for (Cat c : cats) {
            if (mapped.stream().anyMatch(m -> m.categoryId().equals(c.id()))) {
                continue;
            }
            String n = c.name().toLowerCase(Locale.ROOT);
            String s = n.equals("rent") || n.startsWith("house rent") ? "HRA"
                    : n.contains("health insurance") || n.contains("mediclaim") ? "80D"
                    : n.contains("donation") || n.contains("charity") ? "80G"
                    : n.contains("life insurance") || n.equals("ppf") || n.contains("elss") || n.contains("tuition") || n.contains("school fee") ? "80C"
                    : n.equals("nps") ? "80CCD1B"
                    : null;
            if (s != null) {
                out.add(new Mapping(c.id(), s));
            }
        }
        return out;
    }

    private static String cell(String s) {
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    private static String rupees(long paise) {
        return java.math.BigDecimal.valueOf(paise, 2).toPlainString();
    }
}
