package dev.payledger.core.money;

import dev.payledger.core.space.SpaceAccess;
import dev.payledger.core.user.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A report over the transactions a filter matches (the same filter as the list and the exports): totals by category,
 * month, account, day, weekday, tag and place, and the biggest expenses. Amounts are paise in the space's currency;
 * transfers move money between your own accounts and are never counted as spending or income.
 */
@Service
public class ReportService {

    private final JdbcClient jdbc;
    private final SpaceAccess access;

    public ReportService(JdbcClient jdbc, SpaceAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    /**
     * @param days     days in the filter's date range (from the first transaction when there's no start), for averages
     * @param byDay    spending per day, for a heat map
     * @param byWeekday spending per ISO weekday, 1 = Monday
     * @param places   the descriptions spent at most, case-folded ("Swiggy" and "SWIGGY" are one place)
     */
    public record Breakdown(long spent, long earned, int count, int days,
                            List<CategoryTotal> byCategory, List<MonthTotal> byMonth, List<AccountTotal> byAccount,
                            List<DayTotal> byDay, List<WeekdayTotal> byWeekday, List<TagTotal> byTag, List<Place> places,
                            List<Biggest> biggest, List<Recent> recent) {
        public record CategoryTotal(UUID categoryId, String kind, long amount, int count) {
        }

        /** @param month yyyy-mm */
        public record MonthTotal(String month, long spent, long earned) {
        }

        public record AccountTotal(UUID accountId, long spent, long earned, int count) {
        }

        public record DayTotal(LocalDate day, long spent, long earned) {
        }

        public record WeekdayTotal(int weekday, long spent, int count) {
        }

        public record TagTotal(String tag, long spent, int count) {
        }

        public record Place(String name, long spent, int count) {
        }

        public record Biggest(UUID id, String description, long amount, LocalDate occurredOn, UUID categoryId) {
        }

        /** The latest few, newest first, of any kind. */
        public record Recent(UUID id, String kind, String description, long amount, LocalDate occurredOn, UUID categoryId) {
        }
    }

    private static final String FROM = """
            from transactions t
            where t.space_id = :spaceId and t.deleted_at is null
            """;

    @Transactional(readOnly = true)
    public Breakdown breakdown(CurrentUser user, UUID spaceId, TransactionFilter filter) {
        access.read(user, spaceId);
        String where = FROM + TransactionFilter.WHERE;
        record Totals(long spent, long earned, int count, LocalDate first, LocalDate last) {
        }
        Totals totals = query("""
                select coalesce(sum(t.amount) filter (where t.kind = 'EXPENSE'), 0) as spent,
                       coalesce(sum(t.amount) filter (where t.kind = 'INCOME'), 0) as earned,
                       count(*) filter (where t.kind <> 'TRANSFER')::int as count,
                       min(t.occurred_on) as first, max(t.occurred_on) as last
                """ + where, spaceId, filter).query(Totals.class).single();
        LocalDate start = filter.from() != null ? filter.from() : totals.first();
        LocalDate end = filter.to() != null ? filter.to() : totals.last();
        int days = start == null || end == null ? 0 : (int) (end.toEpochDay() - start.toEpochDay() + 1);

        return new Breakdown(totals.spent(), totals.earned(), totals.count(), Math.max(days, 0),
                query("""
                        select t.category_id, t.kind, sum(t.amount) as amount, count(*)::int as count
                        """ + where + " and t.kind <> 'TRANSFER' group by t.category_id, t.kind order by sum(t.amount) desc", spaceId, filter)
                        .query(Breakdown.CategoryTotal.class).list(),
                query("""
                        select to_char(t.occurred_on, 'YYYY-MM') as month,
                               coalesce(sum(t.amount) filter (where t.kind = 'EXPENSE'), 0) as spent,
                               coalesce(sum(t.amount) filter (where t.kind = 'INCOME'), 0) as earned
                        """ + where + " group by 1 order by 1", spaceId, filter)
                        .query(Breakdown.MonthTotal.class).list(),
                query("""
                        select t.account_id,
                               coalesce(sum(t.amount) filter (where t.kind = 'EXPENSE'), 0) as spent,
                               coalesce(sum(t.amount) filter (where t.kind = 'INCOME'), 0) as earned,
                               count(*) filter (where t.kind <> 'TRANSFER')::int as count
                        """ + where + " and t.kind <> 'TRANSFER' group by t.account_id order by 2 desc, 3 desc", spaceId, filter)
                        .query(Breakdown.AccountTotal.class).list(),
                query("""
                        select t.occurred_on as day,
                               coalesce(sum(t.amount) filter (where t.kind = 'EXPENSE'), 0) as spent,
                               coalesce(sum(t.amount) filter (where t.kind = 'INCOME'), 0) as earned
                        """ + where + " and t.kind <> 'TRANSFER' group by t.occurred_on order by t.occurred_on", spaceId, filter)
                        .query(Breakdown.DayTotal.class).list(),
                query("""
                        select extract(isodow from t.occurred_on)::int as weekday, sum(t.amount) as spent, count(*)::int as count
                        """ + where + " and t.kind = 'EXPENSE' group by 1 order by 1", spaceId, filter)
                        .query(Breakdown.WeekdayTotal.class).list(),
                query("""
                        select tag, sum(t.amount) as spent, count(*)::int as count
                        """ + FROM.replace("from transactions t", "from transactions t cross join lateral unnest(t.tags) as tag")
                        + TransactionFilter.WHERE + " and t.kind = 'EXPENSE' group by tag order by sum(t.amount) desc limit 20", spaceId, filter)
                        .query(Breakdown.TagTotal.class).list(),
                query("""
                        select min(trim(t.description)) as name, sum(t.amount) as spent, count(*)::int as count
                        """ + where + " and t.kind = 'EXPENSE' group by lower(trim(t.description)) order by sum(t.amount) desc limit 10", spaceId, filter)
                        .query(Breakdown.Place.class).list(),
                query("""
                        select t.id, t.description, t.amount, t.occurred_on, t.category_id
                        """ + where + " and t.kind = 'EXPENSE' order by t.amount desc, t.occurred_on desc limit 10", spaceId, filter)
                        .query(Breakdown.Biggest.class).list(),
                query("""
                        select t.id, t.kind, t.description, t.amount, t.occurred_on, t.category_id
                        """ + where + " order by t.occurred_on desc, t.created_at desc limit 3", spaceId, filter)
                        .query(Breakdown.Recent.class).list());
    }

    private JdbcClient.StatementSpec query(String sql, UUID spaceId, TransactionFilter filter) {
        return filter.bind(jdbc.sql(sql)).param("spaceId", spaceId);
    }
}
