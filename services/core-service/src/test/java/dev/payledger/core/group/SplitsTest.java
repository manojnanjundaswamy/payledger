package dev.payledger.core.group;

import dev.payledger.core.common.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SplitsTest {

    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    final UUID c = UUID.randomUUID();

    @Test
    void equalSplitsGiveTheLeftoverPaiseToTheFirstPeople() {
        // ₹100 three ways: 33.34 + 33.33 + 33.33.
        assertThat(Splits.split(10_000, Splits.Type.EQUAL, parts(0, 0, 0))).containsExactly(
                Map.entry(a, 3_334L), Map.entry(b, 3_333L), Map.entry(c, 3_333L));
        assertThat(Splits.split(2, Splits.Type.EQUAL, parts(0, 0, 0)).values()).containsExactly(1L, 1L, 0L);
    }

    @Test
    void exactAmountsMustAddUp() {
        assertThat(Splits.split(1_000, Splits.Type.EXACT, parts(600, 400, 0)).values()).containsExactly(600L, 400L, 0L);
        assertThatThrownBy(() -> Splits.split(1_000, Splits.Type.EXACT, parts(600, 300, 0)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("add up");
    }

    @Test
    void percentagesAreInBasisPointsAndMustMakeAHundred() {
        // 50% / 30% / 20% of ₹99.99.
        Map<UUID, Long> split = Splits.split(9_999, Splits.Type.PERCENT, parts(5_000, 3_000, 2_000));
        assertThat(split.values()).containsExactly(5_000L, 3_000L, 1_999L);
        assertThat(split.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(9_999);
        assertThatThrownBy(() -> Splits.split(9_999, Splits.Type.PERCENT, parts(5_000, 3_000, 1_000)))
                .hasMessageContaining("100");
    }

    @Test
    void sharesAreWeightsAndAZeroWeightTakesNothing() {
        // 2 shares, 1 share, 0 shares of ₹10.
        assertThat(Splits.split(1_000, Splits.Type.SHARES, parts(2, 1, 0)).values()).containsExactly(667L, 333L, 0L);
        assertThatThrownBy(() -> Splits.split(1_000, Splits.Type.SHARES, parts(0, 0, 0))).hasMessageContaining("not all zero");
    }

    @Test
    void payersMustAddUpOnceEach() {
        assertThat(Splits.payers(1_000, List.of(new Splits.Part(a, 700), new Splits.Part(b, 300)))).containsEntry(a, 700L);
        assertThatThrownBy(() -> Splits.payers(1_000, List.of(new Splits.Part(a, 700)))).hasMessageContaining("add up");
        assertThatThrownBy(() -> Splits.payers(1_000, List.of(new Splits.Part(a, 500), new Splits.Part(a, 500)))).hasMessageContaining("once");
        assertThatThrownBy(() -> Splits.split(1_000, Splits.Type.EQUAL, List.of(new Splits.Part(a, 0), new Splits.Part(a, 0))))
                .hasMessageContaining("twice");
    }

    @Test
    void simplifyingFindsTheFewestPayments() {
        // A is owed ₹600; B owes ₹400 and C owes ₹200: two payments, both to A.
        Map<UUID, Long> balances = new LinkedHashMap<>();
        balances.put(a, -60_000L);
        balances.put(b, 40_000L);
        balances.put(c, 20_000L);
        assertThat(Splits.simplify(balances)).containsExactlyInAnyOrder(
                new Splits.Payment(b, a, 40_000), new Splits.Payment(c, a, 20_000));
        assertThat(Splits.simplify(Map.of(a, 0L, b, 0L))).isEmpty();
    }

    private List<Splits.Part> parts(long x, long y, long z) {
        return List.of(new Splits.Part(a, x), new Splits.Part(b, y), new Splits.Part(c, z));
    }
}
