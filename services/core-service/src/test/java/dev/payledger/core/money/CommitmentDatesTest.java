package dev.payledger.core.money;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CommitmentDatesTest {

    private static Commitment every(int months, String firstDue, String endsOn) {
        return new Commitment(UUID.randomUUID(), "BILL", "EMI", 100, "INR", UUID.randomUUID(), UUID.randomUUID(), null,
                months, LocalDate.parse(firstDue), endsOn == null ? null : LocalDate.parse(endsOn), 1);
    }

    @Test
    void theThirtyFirstFallsOnTheMonthsLastDayAndComesBack() {
        assertThat(every(1, "2026-01-31", null).dueDates(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-04-30")))
                .containsExactly(LocalDate.parse("2026-01-31"), LocalDate.parse("2026-02-28"),
                        LocalDate.parse("2026-03-31"), LocalDate.parse("2026-04-30"));
    }

    @Test
    void anEmiStopsAfterItsLastInstalment() {
        assertThat(every(1, "2026-01-05", "2026-03-05").dueDates(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31")))
                .containsExactly(LocalDate.parse("2026-01-05"), LocalDate.parse("2026-02-05"), LocalDate.parse("2026-03-05"));
    }

    @Test
    void quarterlyAndYearlyOnlyFallInTheirMonths() {
        Commitment quarterly = every(3, "2025-11-10", null);
        assertThat(quarterly.dueDates(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31")))
                .containsExactly(LocalDate.parse("2026-02-10"), LocalDate.parse("2026-05-10"),
                        LocalDate.parse("2026-08-10"), LocalDate.parse("2026-11-10"));
        Commitment yearly = every(12, "2024-02-29", null);
        assertThat(yearly.dueDates(LocalDate.parse("2026-01-01"), LocalDate.parse("2028-12-31")))
                .containsExactly(LocalDate.parse("2026-02-28"), LocalDate.parse("2027-02-28"), LocalDate.parse("2028-02-29"));
        assertThat(quarterly.isDueOn(LocalDate.parse("2026-05-10"))).isTrue();
        assertThat(quarterly.isDueOn(LocalDate.parse("2026-06-10"))).isFalse();
    }

    @Test
    void nothingIsDueBeforeTheFirstDate() {
        assertThat(every(1, "2026-10-05", null).dueDates(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-09-30"))).isEmpty();
    }
}
