package dev.payledger.core.budget;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetMathTest {

    @Test
    void aPeriodRunsFromTheStartDayToTheDayBeforeItNextMonth() {
        BudgetPeriod payday = BudgetPeriod.of(YearMonth.of(2026, 12), 25);
        assertThat(payday.start()).isEqualTo("2026-12-25");
        assertThat(payday.end()).isEqualTo("2027-01-24");
        assertThat(payday.days()).isEqualTo(31);

        BudgetPeriod february = BudgetPeriod.of(YearMonth.of(2028, 2), 1);
        assertThat(february.end()).isEqualTo("2028-02-29");
        assertThat(february.days()).isEqualTo(29);
    }

    @Test
    void theCurrentPeriodDependsOnWhetherTheStartDayHasCome() {
        assertThat(BudgetPeriod.containing(LocalDate.of(2026, 10, 24), 25).month()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(BudgetPeriod.containing(LocalDate.of(2026, 10, 25), 25).month()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(BudgetPeriod.containing(LocalDate.of(2026, 1, 3), 5).month()).isEqualTo(YearMonth.of(2025, 12));
        assertThat(BudgetPeriod.containing(LocalDate.of(2026, 10, 1), 1).month()).isEqualTo(YearMonth.of(2026, 10));
    }

    @Test
    void daysElapsedCountsTodayAndStaysWithinThePeriod() {
        BudgetPeriod october = BudgetPeriod.of(YearMonth.of(2026, 10), 1);
        assertThat(october.daysElapsed(LocalDate.of(2026, 9, 30))).isZero();
        assertThat(october.daysElapsed(LocalDate.of(2026, 10, 1))).isEqualTo(1);
        assertThat(october.daysElapsed(LocalDate.of(2026, 10, 13))).isEqualTo(13);
        assertThat(october.daysElapsed(LocalDate.of(2026, 12, 1))).isEqualTo(31);
    }

    @Test
    void statusIsCloseFrom80PercentAndOverOnlyPastTheLimit() {
        assertThat(BudgetService.status(79_999, 100_000)).isEqualTo(BudgetService.Status.ON_TRACK);
        assertThat(BudgetService.status(80_000, 100_000)).isEqualTo(BudgetService.Status.CLOSE);
        assertThat(BudgetService.status(100_000, 100_000)).isEqualTo(BudgetService.Status.CLOSE);
        assertThat(BudgetService.status(100_001, 100_000)).isEqualTo(BudgetService.Status.OVER);
    }

    @Test
    void thePaceShowsFromTheFifthDayUntilThePeriodEnds() {
        assertThat(BudgetService.projected(1_000_000, 30, 4)).isNull();
        // ₹10,000 in 5 days of 30: ₹60,000 at this rate.
        assertThat(BudgetService.projected(1_000_000, 30, 5)).isEqualTo(6_000_000);
        assertThat(BudgetService.projected(0, 30, 10)).isZero();
        assertThat(BudgetService.projected(1_000_000, 30, 30)).isNull();
    }
}
