package dev.payledger.core.money;

import dev.payledger.core.common.ApiException;
import dev.payledger.core.common.InvalidRequestException;
import dev.payledger.core.space.SpaceAccess;
import dev.payledger.core.space.SpaceAccess.Space;
import dev.payledger.core.user.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

/**
 * Moving a space's money to another currency, in one of two ways the person chooses:
 * <ul>
 *   <li>CONVERT: every amount at an exchange rate (today's, or one they type): ₹1,00,000 becomes $1,198. For moving
 *   country, or deciding to keep books in another currency.</li>
 *   <li>RELABEL: the same numbers in the new currency: ₹1,000 becomes $1,000. For a currency that was set up wrong.</li>
 * </ul>
 * Everything in the space's old currency moves: accounts (opening balances re-posted), every entry (reversed and
 * re-posted, like an edit), budgets, bills and savings, goals and their top-ups, daily services. Accounts kept in
 * another currency on purpose stay as they are, and so does "Friends & groups", which follows each group's currency.
 * All or nothing, in one transaction.
 */
@Service
public class CurrencyChange {

    public enum Mode { CONVERT, RELABEL }

    private final JdbcClient jdbc;
    private final SpaceAccess access;
    private final AccountService accounts;
    private final TransactionService transactions;
    private final FxService fx;

    CurrencyChange(JdbcClient jdbc, SpaceAccess access, AccountService accounts, TransactionService transactions, FxService fx) {
        this.jdbc = jdbc;
        this.access = access;
        this.accounts = accounts;
        this.transactions = transactions;
        this.fx = fx;
    }

    /**
     * What would happen (or what did).
     *
     * @param rate   units of {@code to} for one unit of {@code from} (1 for RELABEL)
     * @param sample an example amount before and after (the biggest balance, or 1,000 of the old currency)
     */
    public record Plan(String from, String to, Mode mode, BigDecimal rate, String rateSource, LocalDate rateDate,
                       int accounts, int entries, int budgets, int bills, int goals, int services, long sampleBefore, long sampleAfter) {
    }

    @Transactional(readOnly = true)
    public Plan preview(CurrentUser user, UUID spaceId, String to, Mode mode, BigDecimal customRate) {
        Space space = access.read(user, spaceId);
        return plan(space, to, mode, customRate);
    }

    @Transactional
    public Plan apply(CurrentUser user, UUID spaceId, String to, Mode mode, BigDecimal customRate) {
        Space space = access.write(user, spaceId);
        if (!"OWNER".equals(space.role()) && !"PARTNER".equals(space.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "owner-only", "only the owner or a partner can change the space's currency") {
            };
        }
        jdbc.sql("select id from spaces where id = :s for update").param("s", spaceId).query(UUID.class).single();
        Plan plan = plan(space, to, mode, customRate);
        String from = plan.from();
        int fromDigits = digits(from);
        int toDigits = digits(to);
        java.util.function.LongUnaryOperator convert = minor -> move(minor, fromDigits, toDigits, plan.rate());

        List<UUID> moving = jdbc.sql("select id from money_accounts where space_id = :s and currency = :c and kind <> 'OWED'")
                .param("s", spaceId).param("c", from).query(UUID.class).list();
        for (UUID account : moving) {
            long opening = jdbc.sql("select opening_balance from money_accounts where id = :id").param("id", account).query(Long.class).single();
            accounts.changeCurrency(space, account, to, convert.applyAsLong(opening));
        }
        if (!moving.isEmpty()) {
            for (UUID txn : jdbc.sql("""
                            select id from transactions where space_id = :s and deleted_at is null and currency = :c and group_link is null
                              and account_id in (:accounts) order by occurred_on, created_at
                            """).param("s", spaceId).param("c", from).param("accounts", moving).query(UUID.class).list()) {
                long amount = jdbc.sql("select amount from transactions where id = :id").param("id", txn).query(Long.class).single();
                transactions.changeCurrency(space, txn, convert.applyAsLong(amount));
            }
        }
        // Plans and limits in the old currency follow; each amount converted the same way.
        for (String table : List.of("budget_limits", "commitments")) {
            jdbc.sql("select amount, ctid::text as row from " + table + " where space_id = :s and currency = :c").param("s", spaceId).param("c", from)
                    .query((rs, n) -> new Object[]{rs.getLong("amount"), rs.getString("row")}).list()
                    .forEach(r -> jdbc.sql("update " + table + " set amount = :a, currency = :to where space_id = :s and ctid = cast(:row as tid)")
                            .param("a", Math.max(convert.applyAsLong((Long) r[0]), 1)).param("to", to).param("s", spaceId).param("row", r[1]).update());
        }
        jdbc.sql("select id, target from goals where space_id = :s").param("s", spaceId)
                .query((rs, n) -> new Object[]{rs.getObject("id", UUID.class), rs.getLong("target")}).list()
                .forEach(g -> jdbc.sql("update goals set target = :t where id = :id").param("t", Math.max(convert.applyAsLong((Long) g[1]), 1)).param("id", g[0]).update());
        jdbc.sql("select c.id, c.amount from goal_contributions c join goals g on g.id = c.goal_id where g.space_id = :s").param("s", spaceId)
                .query((rs, n) -> new Object[]{rs.getObject("id", UUID.class), rs.getLong("amount")}).list()
                .forEach(c -> jdbc.sql("update goal_contributions set amount = :a where id = :id").param("a", convert.applyAsLong((Long) c[1])).param("id", c[0]).update());
        jdbc.sql("select id, rate from daily_services where space_id = :s").param("s", spaceId)
                .query((rs, n) -> new Object[]{rs.getObject("id", UUID.class), rs.getLong("rate")}).list()
                .forEach(d -> jdbc.sql("update daily_services set rate = :r where id = :id").param("r", Math.max(convert.applyAsLong((Long) d[1]), 1)).param("id", d[0]).update());

        jdbc.sql("update spaces set currency = :to where id = :s").param("to", to).param("s", spaceId).update();
        if ("PERSONAL".equals(space.kind())) {
            jdbc.sql("update users set currency = :to, updated_at = now() where id = :u").param("to", to).param("u", user.id()).update();
        }
        return plan;
    }

    private Plan plan(Space space, String to, Mode mode, BigDecimal customRate) {
        String from = space.currency();
        try {
            Currency.getInstance(to);
        } catch (Exception e) {
            throw new InvalidRequestException("invalid-currency", "that isn't a currency code like USD");
        }
        if (from.equals(to)) {
            throw new InvalidRequestException("same-currency", "your money is already in " + to);
        }
        BigDecimal rate;
        String source;
        LocalDate date;
        if (mode == Mode.RELABEL) {
            rate = BigDecimal.ONE;
            source = "Same numbers, new currency";
            date = null;
        } else if (customRate != null) {
            if (customRate.signum() <= 0) {
                throw new InvalidRequestException("invalid-rate", "the rate must be more than zero");
            }
            rate = customRate;
            source = "Your rate";
            date = null;
        } else {
            FxService.Rates r = fx.rates();
            Double perFrom = from.equals(r.base()) ? Double.valueOf(1.0) : r.rates().get(from);
            Double perTo = to.equals(r.base()) ? Double.valueOf(1.0) : r.rates().get(to);
            if (perFrom == null || perTo == null) {
                throw new InvalidRequestException("rate-unknown", "there's no published rate for " + (perFrom == null ? from : to) + "; enter one yourself");
            }
            rate = BigDecimal.valueOf(perTo).divide(BigDecimal.valueOf(perFrom), 10, RoundingMode.HALF_EVEN);
            source = r.source();
            date = r.date();
        }
        UUID s = space.id();
        int accountsCount = count("select count(*) from money_accounts where space_id = :s and currency = :c and kind <> 'OWED'", s, from);
        int entries = count("select count(*) from transactions where space_id = :s and deleted_at is null and currency = :c and group_link is null", s, from);
        int budgets = count("select count(*) from budget_limits where space_id = :s and currency = :c", s, from);
        int bills = count("select count(*) from commitments where space_id = :s and currency = :c", s, from);
        int goals = count("select count(*) from goals where space_id = :s and :c = :c", s, from);
        int services = count("select count(*) from daily_services where space_id = :s and :c = :c", s, from);
        long sample = jdbc.sql("select coalesce(max(abs(opening_balance)), 0) from money_accounts where space_id = :s and currency = :c and kind <> 'OWED'")
                .param("s", s).param("c", from).query(Long.class).single();
        if (sample == 0) {
            sample = 1000 * (long) Math.pow(10, digits(from));
        }
        return new Plan(from, to, mode, rate, source, date, accountsCount, entries, budgets, bills, goals, services,
                sample, move(sample, digits(from), digits(to), rate));
    }

    private int count(String sql, UUID space, String currency) {
        return jdbc.sql(sql).param("s", space).param("c", currency).query(Long.class).single().intValue();
    }

    /** Minor units of one currency to the other's: {@code rate} per whole unit, rounding half to even. */
    static long move(long minor, int fromDigits, int toDigits, BigDecimal rate) {
        return BigDecimal.valueOf(minor).movePointLeft(fromDigits).multiply(rate).movePointRight(toDigits)
                .setScale(0, RoundingMode.HALF_EVEN).longValueExact();
    }

    static int digits(String currency) {
        return Math.max(Currency.getInstance(currency).getDefaultFractionDigits(), 0);
    }
}
