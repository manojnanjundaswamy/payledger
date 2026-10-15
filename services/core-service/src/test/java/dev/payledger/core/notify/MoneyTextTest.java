package dev.payledger.core.notify;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Amounts in messages are in the reader's currency, in whole units. */
class MoneyTextTest {

    @Test
    void rupeesUseIndianGroupingAndOtherCurrenciesTheirOwnUnits() {
        assertThat(Reminders.money(12_345_600)).isEqualTo("₹1,23,456");
        assertThat(Reminders.money(123_400, "AED")).contains("AED").contains("1,234");
        assertThat(Reminders.money(1_500, "JPY")).contains("1,500");          // no minor unit
        assertThat(Reminders.money(2_500_000, "KWD")).contains("2,500");      // three decimals
        assertThat(Reminders.inCurrency("USD", () -> Reminders.money(99_900))).isEqualTo("$999");
        assertThat(Reminders.money(100)).isEqualTo("₹1");                      // back to rupees outside
        assertThat(Reminders.money(1_00_00_000_00L)).isEqualTo("₹1,00,00,000");   // a crore
        assertThat(Reminders.money(99_900)).isEqualTo("₹999");
        assertThat(Reminders.money(-5_000_00)).isEqualTo("-₹5,000");
    }
}
