package dev.payledger.core.money;

import dev.payledger.core.budget.BudgetService;
import dev.payledger.core.common.ApiException;
import dev.payledger.core.common.InvalidRequestException;
import dev.payledger.core.space.SpaceAccess;
import dev.payledger.core.user.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The two-minute setup after sign-up (docs/plan/onboarding-and-features.md): payday, accounts, the regular
 * payments (rent, EMIs, SIPs, subscriptions) and first budgets, in one transaction. Either all of it is there
 * afterwards or none of it, so a failed step never leaves half a setup. Plan limits apply as for any add.
 */
@Service
public class SetupService {

    /**
     * @param kind           BANK, CASH, CARD or WALLET
     * @param openingBalance paise; on a card, what is owed
     */
    public record AccountIn(@NotNull AccountKind kind, @NotBlank @Size(max = 60) String name,
                            @Min(-AccountRequests.MAX_AMOUNT) @Max(AccountRequests.MAX_AMOUNT) Long openingBalance) {
    }

    /**
     * A regular payment.
     *
     * @param kind        BILL (rent, EMI, subscription) or SAVING (a SIP or RD: money moved to savings)
     * @param category    a default category's key: rent, emi, subscriptions, utilities, insurance, education, ... (bills)
     * @param account     index into {@code accounts} it is paid from (default: the first bank account)
     * @param day         day of the month it is due (1-31; short months use their last day)
     * @param everyMonths 1, 2, 3, 4, 6 or 12
     */
    public record PaymentIn(@NotNull @Pattern(regexp = "BILL|SAVING") String kind, @NotBlank @Size(max = 60) String name,
                            @NotNull @Min(1) @Max(AccountRequests.MAX_AMOUNT) Long amount,
                            @Size(max = 40) String category, @Min(0) @Max(19) Integer account,
                            @NotNull @Min(1) @Max(31) Integer day, @NotNull Integer everyMonths, LocalDate endsOn) {
    }

    /** @param category a default category's key (groceries, eating-out, ...) */
    public record BudgetIn(@NotBlank @Size(max = 40) String category, @NotNull @Min(1) @Max(AccountRequests.MAX_AMOUNT) Long amount) {
    }

    /** @param budgetStartDay payday: the day budget months start (1-28) */
    public record Request(@Min(1) @Max(28) Integer budgetStartDay,
                          @NotNull @Size(min = 1, max = 20) List<@Valid AccountIn> accounts,
                          @NotNull @Size(max = 30) List<@Valid PaymentIn> payments,
                          @NotNull @Size(max = 30) List<@Valid BudgetIn> budgets) {
    }

    public record Result(List<MoneyAccount> accounts, List<Commitment> payments, int budgets) {
    }

    private final JdbcClient jdbc;
    private final SpaceAccess access;
    private final AccountService accounts;
    private final CommitmentService commitments;
    private final BudgetService budgets;
    private final ZoneId zone;

    SetupService(JdbcClient jdbc, SpaceAccess access, AccountService accounts, CommitmentService commitments, BudgetService budgets,
                 @org.springframework.beans.factory.annotation.Value("${payledger.business-zone:Asia/Kolkata}") ZoneId zone) {
        this.jdbc = jdbc;
        this.access = access;
        this.accounts = accounts;
        this.commitments = commitments;
        this.budgets = budgets;
        this.zone = zone;
    }

    @Transactional
    public Result setUp(CurrentUser user, UUID spaceId, Request r) {
        access.write(user, spaceId);
        // A setup is for a new space: a second one (a double tap, a retry) must not add everything twice.
        if (jdbc.sql("select exists (select 1 from money_accounts where space_id = :id)").param("id", spaceId).query(Boolean.class).single()) {
            throw new AlreadySetUpException();
        }
        if (r.budgetStartDay() != null) {
            jdbc.sql("update users set budget_start_day = :day, updated_at = now() where id = :id")
                    .param("day", r.budgetStartDay()).param("id", user.id()).update();
        }

        List<MoneyAccount> made = new ArrayList<>();
        LocalDate today = LocalDate.now(zone);
        for (AccountIn a : r.accounts()) {
            made.add(accounts.create(user, spaceId, new AccountRequests.Create(a.kind(), a.name().strip(), null,
                    a.openingBalance() == null ? 0L : a.openingBalance(), today)));
        }
        MoneyAccount firstBank = made.stream().filter(a -> a.kind() == AccountKind.BANK).findFirst().orElse(made.getFirst());

        List<Commitment> planned = new ArrayList<>();
        MoneyAccount savings = null;
        for (PaymentIn p : r.payments()) {
            MoneyAccount from = p.account() == null ? firstBank : at(made, p.account());
            UUID categoryId = null;
            UUID toAccountId = null;
            if (p.kind().equals("BILL")) {
                categoryId = category(spaceId, p.category() == null ? "other-expense" : p.category());
            } else {
                // SIPs and RDs move money into savings; one savings account holds them, made when first needed.
                if (savings == null) {
                    savings = made.stream().filter(a -> a.name().equalsIgnoreCase("Savings and investments")).findFirst()
                            .orElseGet(() -> accounts.create(user, spaceId, new AccountRequests.Create(AccountKind.BANK, "Savings and investments", null, 0L, today)));
                    if (!made.contains(savings)) {
                        made.add(savings);
                    }
                }
                toAccountId = savings.id();
            }
            planned.add(commitments.create(user, spaceId, new CommitmentRequests.Fields(p.kind(), p.name().strip(), p.amount(),
                    from.id(), categoryId, toAccountId, p.everyMonths(), nextDue(today, p.day()), p.endsOn())));
        }

        for (BudgetIn b : r.budgets()) {
            budgets.set(user, spaceId, category(spaceId, b.category()), b.amount(), false, null);
        }
        return new Result(made, planned, r.budgets().size());
    }

    /** The next date on or after today that falls on that day of the month (the month's last day if it is shorter). */
    public static LocalDate nextDue(LocalDate today, int day) {
        LocalDate thisMonth = today.withDayOfMonth(Math.min(day, today.lengthOfMonth()));
        if (!thisMonth.isBefore(today)) {
            return thisMonth;
        }
        LocalDate next = today.plusMonths(1).withDayOfMonth(1);
        return next.withDayOfMonth(Math.min(day, next.lengthOfMonth()));
    }

    private static MoneyAccount at(List<MoneyAccount> made, int index) {
        if (index >= made.size()) {
            throw new InvalidRequestException("setup-invalid", "a payment names an account that isn't in the list");
        }
        return made.get(index);
    }

    /** A default category by its key, as the user has it now (renamed is fine; hidden or deleted is not). */
    private UUID category(UUID spaceId, String slug) {
        return jdbc.sql("select id from categories where space_id = :spaceId and seed_slug = :slug and not hidden and kind = 'EXPENSE'")
                .param("spaceId", spaceId).param("slug", slug).query(UUID.class).optional()
                .orElseThrow(() -> new InvalidRequestException("setup-invalid", "no spending category " + slug));
    }

    static class AlreadySetUpException extends ApiException {
        AlreadySetUpException() {
            super(HttpStatus.CONFLICT, "already-set-up", "this space already has accounts; add more from their pages");
        }
    }
}
