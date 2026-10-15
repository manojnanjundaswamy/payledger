package dev.payledger.core.money;

import dev.payledger.core.common.ApiException;
import dev.payledger.core.common.InvalidRequestException;
import dev.payledger.core.common.NotFoundException;
import dev.payledger.core.common.Uuid7;
import dev.payledger.core.space.SpaceAccess;
import dev.payledger.core.space.SpaceAccess.Space;
import dev.payledger.core.user.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Daily services (product plan 2.1): the milkman, the newspaper, a cook or a maid. A rate per day (a normal day's
 * delivery) or per month (a salary, less leave beyond the paid days off). Only the days that differ are marked
 * (0 missed, 2 double…), the month's bill follows, and paying it records one expense like any other.
 */
@Service
public class DailyServices {

    private final JdbcClient jdbc;
    private final SpaceAccess access;
    private final TransactionService transactions;
    private final AccountService accounts;

    DailyServices(JdbcClient jdbc, SpaceAccess access, TransactionService transactions, AccountService accounts) {
        this.jdbc = jdbc;
        this.access = access;
        this.transactions = transactions;
        this.accounts = accounts;
    }

    public record DailyService(UUID id, String name, String emoji, long rate, String per, int paidLeaves, UUID accountId,
                               UUID categoryId, LocalDate startedOn, LocalDate endedOn) {
    }

    /**
     * A month's bill.
     *
     * @param days    days the service ran this month
     * @param units   DAY: deliveries counted (a double day is 2); MONTH: days worked
     * @param missed  days marked 0
     * @param extra   extra deliveries beyond one a day
     * @param amount  the month's bill (future days counted as normal)
     * @param soFar   the bill up to today
     * @param marks   the days that differ from normal: date → quantity
     */
    public record Bill(String month, int days, int units, int missed, int extra, long amount, long soFar, Paid paid,
                       Map<LocalDate, Integer> marks) {
    }

    public record Paid(UUID transactionId, long amount) {
    }

    public record Row(DailyService service, Bill bill) {
    }

    public record Fields(String name, String emoji, Long rate, String per, Integer paidLeaves, UUID accountId, UUID categoryId,
                         LocalDate startedOn, LocalDate endedOn) {
    }

    private static final String COLUMNS = "id, name, emoji, rate, per, paid_leaves, account_id, category_id, started_on, ended_on";

    @Transactional(readOnly = true)
    public List<Row> list(CurrentUser user, UUID spaceId, YearMonth month) {
        access.read(user, spaceId);
        YearMonth m = month != null ? month : YearMonth.from(accounts.today(user));
        return jdbc.sql("select " + COLUMNS + " from daily_services where space_id = :s and (ended_on is null or ended_on >= :from) order by created_at")
                .param("s", spaceId).param("from", m.atDay(1)).query(DailyService.class).list().stream()
                .map(d -> new Row(d, bill(d, m, accounts.today(user)))).toList();
    }

    @Transactional
    public Row create(CurrentUser user, UUID spaceId, Fields f) {
        Space space = access.write(user, spaceId);
        String name = name(f.name());
        if (f.rate() == null || f.rate() <= 0 || f.rate() > AccountRequests.MAX_AMOUNT) {
            throw new InvalidRequestException("invalid-rate", "enter the rate");
        }
        String per = "MONTH".equals(f.per()) ? "MONTH" : "DAY";
        checkLinks(space, f);
        UUID id = Uuid7.next();
        LocalDate today = accounts.today(user);
        jdbc.sql("""
                        insert into daily_services (id, space_id, name, emoji, rate, per, paid_leaves, account_id, category_id, started_on, created_by)
                        values (:id, :s, :name, :emoji, :rate, :per, :leaves, :account, :category, :start, :u)
                        """).param("id", id).param("s", spaceId).param("name", name).param("emoji", blank(f.emoji()))
                .param("rate", f.rate()).param("per", per).param("leaves", leaves(f.paidLeaves())).param("account", f.accountId())
                .param("category", f.categoryId()).param("start", f.startedOn() != null ? f.startedOn() : today.withDayOfMonth(1))
                .param("u", user.id()).update();
        DailyService d = find(spaceId, id);
        return new Row(d, bill(d, YearMonth.from(today), today));
    }

    @Transactional
    public Row update(CurrentUser user, UUID spaceId, UUID id, Fields f) {
        Space space = access.write(user, spaceId);
        DailyService was = find(spaceId, id);
        checkLinks(space, f);
        jdbc.sql("""
                        update daily_services set name = :name, emoji = :emoji, rate = :rate, per = :per, paid_leaves = :leaves,
                            account_id = :account, category_id = :category, started_on = :start, ended_on = :end
                        where id = :id
                        """).param("id", id)
                .param("name", f.name() != null ? name(f.name()) : was.name())
                .param("emoji", f.emoji() != null ? blank(f.emoji()) : was.emoji())
                .param("rate", f.rate() != null && f.rate() > 0 ? f.rate() : was.rate())
                .param("per", f.per() != null ? ("MONTH".equals(f.per()) ? "MONTH" : "DAY") : was.per())
                .param("leaves", f.paidLeaves() != null ? leaves(f.paidLeaves()) : was.paidLeaves())
                .param("account", f.accountId() != null ? f.accountId() : was.accountId())
                .param("category", f.categoryId() != null ? f.categoryId() : was.categoryId())
                .param("start", f.startedOn() != null ? f.startedOn() : was.startedOn())
                .param("end", f.endedOn() != null ? f.endedOn() : was.endedOn())
                .update();
        DailyService d = find(spaceId, id);
        LocalDate today = accounts.today(user);
        return new Row(d, bill(d, YearMonth.from(today), today));
    }

    /** Gone for good if it was never paid; otherwise it stops today, and its paid months stay. */
    @Transactional
    public void remove(CurrentUser user, UUID spaceId, UUID id) {
        access.write(user, spaceId);
        find(spaceId, id);
        boolean paid = jdbc.sql("select exists (select 1 from daily_service_payments where service_id = :id)").param("id", id).query(Boolean.class).single();
        if (paid) {
            jdbc.sql("update daily_services set ended_on = :today where id = :id").param("today", accounts.today(user)).param("id", id).update();
        } else {
            jdbc.sql("delete from daily_services where id = :id").param("id", id).update();
        }
    }

    /** Marks a day: 0 missed, 1 normal (the mark goes), 2+ extra. Not in a month that's already paid. */
    @Transactional
    public Row mark(CurrentUser user, UUID spaceId, UUID id, LocalDate day, int quantity) {
        access.write(user, spaceId);
        DailyService d = find(spaceId, id);
        if (quantity < 0 || quantity > 9 || ("MONTH".equals(d.per()) && quantity > 1)) {
            throw new InvalidRequestException("invalid-quantity", "MONTH".equals(d.per()) ? "a day is worked (1) or off (0)" : "between 0 and 9 a day");
        }
        if (day.isBefore(d.startedOn()) || d.endedOn() != null && day.isAfter(d.endedOn())) {
            throw new InvalidRequestException("outside-service", "that day is outside when the service ran");
        }
        if (paidMonth(id, YearMonth.from(day)) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "month-paid", "that month is paid; undo the payment to change its days") {
            };
        }
        if (quantity == 1) {
            jdbc.sql("delete from daily_service_days where service_id = :id and day = :day").param("id", id).param("day", day).update();
        } else {
            jdbc.sql("""
                            insert into daily_service_days (service_id, day, quantity) values (:id, :day, :q)
                            on conflict (service_id, day) do update set quantity = excluded.quantity
                            """).param("id", id).param("day", day).param("q", quantity).update();
        }
        return new Row(d, bill(d, YearMonth.from(day), accounts.today(user)));
    }

    /** Records the month's bill as an expense from the service's account (or the one given), once. */
    @Transactional
    public Row pay(CurrentUser user, UUID spaceId, UUID id, YearMonth month, UUID accountId, LocalDate occurredOn) {
        access.write(user, spaceId);
        DailyService d = find(spaceId, id);
        if (paidMonth(id, month) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "already-paid", "this month is already paid") {
            };
        }
        LocalDate today = accounts.today(user);
        Bill b = bill(d, month, today);
        if (b.amount() <= 0) {
            throw new InvalidRequestException("nothing-to-pay", "nothing is due for that month");
        }
        UUID account = accountId != null ? accountId : d.accountId();
        if (account == null) {
            throw new InvalidRequestException("account-required", "choose the account you paid from");
        }
        UUID category = d.categoryId() != null ? d.categoryId() : jdbc.sql("select id from categories where space_id = :s and seed_slug = 'household-help'")
                .param("s", spaceId).query(UUID.class).optional().orElse(null);
        String label = month.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + month.getYear();
        String detail = "DAY".equals(d.per())
                ? b.units() + (b.units() == 1 ? " delivery" : " deliveries") + (b.missed() > 0 ? ", " + b.missed() + " missed" : "")
                : b.units() + " days worked" + (b.missed() > 0 ? ", " + b.missed() + " off" : "");
        UUID txn = Uuid7.next();
        transactions.create(user, spaceId, new TransactionRequests.Create(txn, "EXPENSE", b.amount(), account, null, category,
                occurredOn != null ? occurredOn : (month.atEndOfMonth().isAfter(today) ? today : month.atEndOfMonth()),
                truncate(d.name() + ": " + label + " (" + detail + ")"), null, List.of("daily-service"), null, null));
        jdbc.sql("insert into daily_service_payments (service_id, month, transaction_id, amount) values (:id, :m, :t, :a)")
                .param("id", id).param("m", month.atDay(1)).param("t", txn).param("a", b.amount()).update();
        return new Row(d, bill(d, month, today));
    }

    /** Undoes a payment: its expense is deleted (through the ledger) and the month opens again. */
    @Transactional
    public Row unpay(CurrentUser user, UUID spaceId, UUID id, YearMonth month) {
        access.write(user, spaceId);
        DailyService d = find(spaceId, id);
        Paid p = paidMonth(id, month);
        if (p == null) {
            throw new NotFoundException("payment");
        }
        jdbc.sql("delete from daily_service_payments where service_id = :id and month = :m").param("id", id).param("m", month.atDay(1)).update();
        Integer version = jdbc.sql("select version from transactions where id = :t and deleted_at is null").param("t", p.transactionId())
                .query(Integer.class).optional().orElse(null);
        if (version != null) {
            transactions.delete(user, spaceId, p.transactionId(), version);
        }
        return new Row(d, bill(d, month, accounts.today(user)));
    }

    // ---------------------------------------------------------------- the bill

    Bill bill(DailyService d, YearMonth month, LocalDate today) {
        LocalDate from = max(month.atDay(1), d.startedOn());
        LocalDate to = d.endedOn() == null ? month.atEndOfMonth() : min(month.atEndOfMonth(), d.endedOn());
        Map<LocalDate, Integer> marks = new LinkedHashMap<>();
        jdbc.sql("select day, quantity from daily_service_days where service_id = :id and day between :from and :to order by day")
                .param("id", d.id()).param("from", month.atDay(1)).param("to", month.atEndOfMonth())
                .query((rs, n) -> marks.put(rs.getObject("day", LocalDate.class), rs.getInt("quantity"))).list();
        Paid paid = paidMonth(d.id(), month);
        if (from.isAfter(to)) {
            return new Bill(month.toString(), 0, 0, 0, 0, 0, 0, paid, marks);
        }
        int days = (int) (to.toEpochDay() - from.toEpochDay()) + 1;
        long amount = amount(d, month, from, to, marks);
        LocalDate soFarTo = min(to, today);
        long soFar = soFarTo.isBefore(from) ? 0 : amount(d, month, from, soFarTo, marks);
        int units = 0;
        int missed = 0;
        int extra = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            int q = marks.getOrDefault(day, 1);
            units += q;
            missed += q == 0 ? 1 : 0;
            extra += Math.max(q - 1, 0);
        }
        return new Bill(month.toString(), days, units, missed, extra, amount, soFar, paid, marks);
    }

    private static long amount(DailyService d, YearMonth month, LocalDate from, LocalDate to, Map<LocalDate, Integer> marks) {
        int units = 0;
        int off = 0;
        int days = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            int q = marks.getOrDefault(day, 1);
            units += q;
            off += q == 0 ? 1 : 0;
            days++;
        }
        if ("DAY".equals(d.per())) {
            return d.rate() * units;
        }
        // A salary: the days it ran, less days off beyond the paid ones, each worth a day of the month's rate.
        int inMonth = month.lengthOfMonth();
        int unpaid = Math.max(off - d.paidLeaves(), 0);
        return Math.max(Math.round((double) d.rate() * (days - unpaid) / inMonth), 0);
    }

    private Paid paidMonth(UUID id, YearMonth month) {
        return jdbc.sql("select transaction_id, amount from daily_service_payments where service_id = :id and month = :m")
                .param("id", id).param("m", month.atDay(1)).query(Paid.class).optional().orElse(null);
    }

    private DailyService find(UUID spaceId, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from daily_services where id = :id and space_id = :s").param("id", id).param("s", spaceId)
                .query(DailyService.class).optional().orElseThrow(() -> new NotFoundException("daily-service"));
    }

    private void checkLinks(Space space, Fields f) {
        if (f.accountId() != null) {
            accounts.requireOpen(space.id(), f.accountId());
        }
        if (f.categoryId() != null && !jdbc.sql("select exists (select 1 from categories where id = :c and space_id = :s and kind = 'EXPENSE')")
                .param("c", f.categoryId()).param("s", space.id()).query(Boolean.class).single()) {
            throw new InvalidRequestException("invalid-category", "choose one of your spending categories");
        }
    }

    private static String name(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 60) {
            throw new InvalidRequestException("invalid-name", "name the service (up to 60 letters)");
        }
        return name.strip();
    }

    private static int leaves(Integer n) {
        return n == null ? 0 : Math.max(0, Math.min(n, 31));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String truncate(String s) {
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
