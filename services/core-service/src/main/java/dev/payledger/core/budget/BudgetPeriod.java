package dev.payledger.core.budget;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * One budget month. It is named by the month it starts in and runs from the user's start day (1-28,
 * often payday) to the day before the same day next month: with start day 25, 2026-10 is 25 Oct to 24 Nov.
 */
public record BudgetPeriod(YearMonth month, LocalDate start, LocalDate end) {

    public static BudgetPeriod of(YearMonth month, int startDay) {
        return new BudgetPeriod(month, month.atDay(startDay), month.plusMonths(1).atDay(startDay).minusDays(1));
    }

    /** The period that contains {@code day}. */
    public static BudgetPeriod containing(LocalDate day, int startDay) {
        YearMonth month = YearMonth.from(day);
        return of(day.getDayOfMonth() >= startDay ? month : month.minusMonths(1), startDay);
    }

    public int days() {
        return (int) ChronoUnit.DAYS.between(start, end) + 1;
    }

    /** Days gone by on {@code today}, today included: 0 before the period, {@link #days()} after it. */
    public int daysElapsed(LocalDate today) {
        if (today.isBefore(start)) {
            return 0;
        }
        return (int) Math.min(days(), ChronoUnit.DAYS.between(start, today) + 1);
    }
}
