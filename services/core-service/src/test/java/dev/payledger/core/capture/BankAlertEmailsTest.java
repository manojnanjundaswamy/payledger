package dev.payledger.core.capture;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Real alert emails (October 2026, anonymised), as subject + body text: each is the transaction it reports. */
class BankAlertEmailsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    /** Axis's footer, word for word: it mentions OTP and "offers", and is about neither. */
    private static final String AXIS_FOOTER = """
            If this transaction was not initiated by you:
            To block UPI: SMS BLOCKUPI <Customer ID> to +919951860002 from your registered mobile number.
            Always open to help you.
            Regards, Axis Bank Ltd.
            Please do not share your Internet Banking details, such as user ID/password or your Credit/Debit Card number/CVV/OTP with anyone, either over phone or through email.
            RBI never deals with individuals for Savings Account, Current Account, Credit Card, Debit Card, etc. Don't be victim to such offers coming to you on phone or email in the name of RBI.
            Do not click on Links from unknown/unsecure Sources that seek your confidential information.
            """;

    @Test
    void axisDebit() {
        String text = """
                INR 10000.00 was debited from your A/c no. XX1234.
                15-10-2026
                Dear Customer,
                Here's the summary of your transaction:
                Amount Debited:
                INR 10000.00
                Account Number:
                XX1234
                Date & Time:
                15-10-26, 21:37:31 IST
                Transaction Info:
                UPI/P2A/628850606094/ASHA K
                """ + AXIS_FOOTER;
        assertThat(AlertParser.noise(text)).isFalse();
        AlertParser.Parsed p = AlertParser.parse(text, "alerts@axis.bank.in", TODAY);
        assertThat(p).isNotNull();
        assertThat(p.kind()).isEqualTo("EXPENSE");
        assertThat(p.amount()).isEqualTo(1_000_000L);
        assertThat(p.merchant()).isEqualTo("Asha K");
        assertThat(p.bank()).isEqualTo("Axis Bank");
        assertThat(p.accountHint()).isEqualTo("1234");
    }

    @Test
    void axisCredit() {
        String text = """
                INR 10000.00 was credited to your A/c.
                Dear Customer,
                Here's the summary of your transaction:
                Amount Credited:
                INR 10000.00
                Account Number:
                XX1234
                Date & Time:
                15-10-26, 19:47:13 IST
                Transaction Info:
                UPI/P2A/665486383803/ASHA K
                """ + AXIS_FOOTER;
        AlertParser.Parsed p = AlertParser.parse(text, "alerts@axis.bank.in", TODAY);
        assertThat(p).isNotNull();
        assertThat(p.kind()).isEqualTo("INCOME");
        assertThat(p.amount()).isEqualTo(1_000_000L);
    }

    @Test
    void hdfcCreditToAccount() {
        String text = """
                View: Account update for your HDFC Bank A/c
                Dear Customer, Greetings from HDFC Bank! We're writing to inform you that Rs.10000.00 has been successfully credited
                to your HDFC Bank account ending in 1234. Transaction Details: a. Date: 15-10-26
                """;
        AlertParser.Parsed p = AlertParser.parse(text, "alerts@hdfcbank.bank.in", TODAY);
        assertThat(p).isNotNull();
        assertThat(p.kind()).isEqualTo("INCOME");
        assertThat(p.amount()).isEqualTo(1_000_000L);
        assertThat(p.accountHint()).isEqualTo("1234");
    }

    @Test
    void anOtpEmailIsStillNoiseEvenWithTheSameFooter() {
        String text = """
                OTP for your transaction
                123456 is the OTP for your transaction of INR 2,500.00 at AMAZON on your card XX1234. Valid for 5 minutes.
                """ + AXIS_FOOTER;
        assertThat(AlertParser.noise(text)).isTrue();
    }

    @Test
    void aDeclineIsStillNoise() {
        assertThat(AlertParser.noise("""
                Transaction declined
                Your transaction of INR 500.00 at SWIGGY was declined due to insufficient balance. Amount will not be debited.
                """ + AXIS_FOOTER)).isTrue();
    }
}
