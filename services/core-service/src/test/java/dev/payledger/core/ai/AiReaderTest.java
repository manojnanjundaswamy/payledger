package dev.payledger.core.ai;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The model's answer is checked against the text before it's trusted: money it invents or dates out of range lower its confidence. */
class AiReaderTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final AiReader.Context CTX = new AiReader.Context(List.of("HDFC Savings", "Axis Bank", "Cash"),
            List.of("Fuel", "Food", "Other"), List.of("Salary", "Other"), List.of(), "INR", TODAY, false);
    private final AiReader reader = new AiReader(new AiConfig.NoLlm(), null, new AiConfig.Prices(1, 5, 0.006), JsonMapper.builder().build());

    @Test
    void aSureAnswerStaysSure() {
        AiReader.Reading r = reader.parse("""
                {"cleanText":"450 petrol yesterday on HDFC Savings","isTransaction":true,"reason":"",
                 "entries":[{"kind":"EXPENSE","amount":"450","currency":"INR","date":"2026-10-14","dateGiven":true,"description":"Petrol",
                  "merchant":null,"account":"hdfc savings","toAccount":null,"category":"Fuel","counterparty":null,"confidence":0.97,"missing":[]}]}
                """, "four fifty petrol yesterday on HDFC savings 450", CTX);
        assertThat(r.transaction()).isTrue();
        AiReader.Entry e = r.entries().getFirst();
        assertThat(e.amount()).isEqualTo(45_000L);
        assertThat(e.account()).isEqualTo("HDFC Savings");
        assertThat(e.category()).isEqualTo("Fuel");
        assertThat(e.date()).isEqualTo(TODAY.minusDays(1));
        assertThat(e.confidence()).isGreaterThanOrEqualTo(AiReader.SURE);
    }

    @Test
    void anAmountNotInTheTextIsNeverTrusted() {
        AiReader.Reading r = reader.parse("""
                {"cleanText":"","isTransaction":true,"entries":[{"kind":"EXPENSE","amount":"999","date":"2026-10-15","description":"x","confidence":0.99}]}
                """, "spent 450 on lunch", CTX);
        assertThat(r.entries().getFirst().confidence()).isLessThanOrEqualTo(0.5);
        assertThat(r.entries().getFirst().checks()).contains("amount-not-in-text");
    }

    @Test
    void madeUpAccountsAndFutureDatesAreDropped() {
        AiReader.Entry e = reader.parse("""
                ```json
                {"isTransaction":true,"entries":[{"kind":"income","amount":"50000","date":"2027-01-01","description":"Salary",
                 "account":"ICICI Salary","category":"Salary","confidence":0.98}]}
                ```
                """, "got 50k salary", CTX).entries().getFirst();
        assertThat(e.kind()).isEqualTo("INCOME");
        assertThat(e.amount()).isEqualTo(5_000_000L);
        assertThat(e.account()).isNull();
        assertThat(e.date()).isEqualTo(TODAY);
        assertThat(e.confidence()).isLessThanOrEqualTo(0.7);
    }

    @Test
    void notATransactionHasNoEntries() {
        AiReader.Reading r = reader.parse("""
                {"cleanText":"","isTransaction":false,"reason":"OTP","entries":[]}
                """, "123456 is your OTP", CTX);
        assertThat(r.transaction()).isFalse();
        assertThat(r.entries()).isEmpty();
    }

    @Test
    void amountsAsPeopleWriteThem() {
        assertThat(AiReader.amountInText(1_000_000, "INR 10,000.00 debited")).isTrue();
        assertThat(AiReader.amountInText(1_000_000, "Rs.10000.00 debited")).isTrue();
        assertThat(AiReader.amountInText(15_000_000, "paid 1.5L rent")).isTrue();
        assertThat(AiReader.amountInText(21_750, "Rs 217.50 at Zomato")).isTrue();
        assertThat(AiReader.amountInText(21_750, "Rs 2175 at Zomato")).isFalse();
        assertThat(AiReader.minor("1,234.5")).isEqualTo(123_450L);
        assertThat(AiReader.minor("12abc")).isNull();
    }
}
