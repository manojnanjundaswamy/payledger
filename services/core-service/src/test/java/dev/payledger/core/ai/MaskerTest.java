package dev.payledger.core.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Nothing that identifies a person or an account reaches a model; amounts, dates and merchants do. */
class MaskerTest {

    @Test
    void hdfcAlertKeepsTheMoneyAndHidesTheRest() {
        Masker.Masked m = Masker.mask("Rs.10000.00 is debited from your account ending 0474 towards VPA ****************my-3@okhdfcbank "
                + "(MANOJ M N) on 15-10-26. UPI transaction reference no.: 665486383803. Call 1800 258 6161 or +91 9876543210.", "Manoj Nanjundaswamy");
        assertThat(m.text())
                .contains("Rs.10000.00", "15-10-26", "account ending [ACCOUNT]", "[UPI ID]", "[YOU]", "[NUMBER]", "[PHONE]")
                .doesNotContain("665486383803", "my-3@okhdfcbank", "MANOJ", "9876543210");
        assertThat(m.masked()).contains("upi", "number", "phone", "name");
    }

    @Test
    void maskedAccountsAndCardsBecomePlaceholders() {
        assertThat(Masker.mask("INR 10000.00 was debited from your A/c no. XX1180.", null).text())
                .isEqualTo("INR 10000.00 was debited from your A/c no. [ACCOUNT].");
        assertThat(Masker.mask("Card 4111 1111 1111 1111 used", null).text()).isEqualTo("Card [CARD] used");
        assertThat(Masker.mask("spent on card **2365 at AMAZON", null).text()).isEqualTo("spent on card [ACCOUNT] at AMAZON");
    }

    @Test
    void bigAmountsAreNotMistakenForNumbers() {
        assertThat(Masker.mask("Rs 150000000 credited", null).text()).isEqualTo("Rs 150000000 credited");
        assertThat(Masker.mask("INR 1,25,00,000.00 credited", null).text()).isEqualTo("INR 1,25,00,000.00 credited");
        assertThat(Masker.mask("got 50000 salary", null).text()).isEqualTo("got 50000 salary");
    }

    @Test
    void merchantHandlesStayPeopleAndEmailsGo() {
        assertThat(Masker.mask("paid to swiggy@icici", null).text()).isEqualTo("paid to swiggy@icici");
        assertThat(Masker.mask("paid to 9876543210@ybl", null).text()).isEqualTo("paid to [UPI ID]");
        assertThat(Masker.mask("paid to asha.k@oksbi", null).text()).isEqualTo("paid to [UPI ID]");
        assertThat(Masker.mask("mail me at asha@example.com", null).text()).isEqualTo("mail me at [EMAIL]");
    }

    @Test
    void ownNameOnlyNotOtherPeople() {
        assertThat(Masker.mask("paid Ravi 1200 for dinner with Manoj", "Manoj Nanjundaswamy").text()).isEqualTo("paid Ravi 1200 for dinner with [YOU]");
    }
}
