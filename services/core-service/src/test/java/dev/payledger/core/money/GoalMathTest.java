package dev.payledger.core.money;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** On track means as far along as the time gone by; the monthly amount spreads what's left over the months left. */
class GoalMathTest {

    private static GoalService.Goal view(long target, LocalDate start, LocalDate by, long saved, LocalDate today) {
        var row = new GoalService.Row(UUID.randomUUID(), "Goal", null, target, by, null, 1, start.atStartOfDay().atOffset(ZoneOffset.UTC), false, saved);
        return GoalService.view(row, saved, today);
    }

    @Test
    void halfwayThroughTheTimeNeedsHalfTheMoney() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate by = LocalDate.of(2026, 12, 31);
        LocalDate midYear = LocalDate.of(2026, 7, 2);
        assertThat(view(1_200_000, start, by, 600_000, midYear).status()).isEqualTo("ON_TRACK");
        var behind = view(1_200_000, start, by, 500_000, midYear);
        assertThat(behind.status()).isEqualTo("BEHIND");
        assertThat(behind.behindBy()).isBetween(99_000L, 101_000L);
        // July to December: 6 months for the 7,00,000 left.
        assertThat(behind.perMonth()).isEqualTo(116_667);
    }

    @Test
    void aPastDateAsksForTheRestNowAndNoDateIsOpen() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        assertThat(view(100_000, start, LocalDate.of(2026, 3, 31), 40_000, LocalDate.of(2026, 5, 10)).perMonth()).isEqualTo(60_000);
        assertThat(view(100_000, start, null, 40_000, LocalDate.of(2026, 5, 10)).status()).isEqualTo("OPEN");
        assertThat(view(100_000, start, null, 100_000, LocalDate.of(2026, 5, 10)).status()).isEqualTo("REACHED");
    }
}
