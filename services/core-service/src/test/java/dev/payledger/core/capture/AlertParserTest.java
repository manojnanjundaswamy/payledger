package dev.payledger.core.capture;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Real-world shapes of Indian bank alerts (names and numbers changed). */
class AlertParserTest {

    static final LocalDate RECEIVED = LocalDate.of(2026, 10, 15);

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            HDFC UPI|Rs.450.00 debited from a/c **1234 on 14-10-26 to VPA swiggy@icici (UPI Ref No 428910283746). Not you? Call 18002586161 - HDFC Bank|EXPENSE|45000|2026-10-14|Swiggy|HDFC Bank|1234
            HDFC card|Alert: You've spent Rs. 1,299.00 on HDFC Bank Credit Card ending 5678 at AMAZON INDIA on 14-10-2026:10:20:11.|EXPENSE|129900|2026-10-14|Amazon India|HDFC Bank|5678
            HDFC credit|Update! INR 1,20,000.00 deposited in HDFC Bank A/c XX1234 on 01-OCT-26 for NEFT Cr-ACME SOFTWARE PVT LTD. Avl bal INR 1,45,210.50.|INCOME|12000000|2026-10-01|Acme Software Pvt Ltd|HDFC Bank|1234
            ICICI UPI|ICICI Bank Acct XX123 debited for Rs 350.00 on 14-Oct-26; Uber credited. UPI:428912345678. Call 18002662 for dispute. SMS BLOCK 123 to 9215676766.|EXPENSE|35000|2026-10-14||ICICI Bank|123
            ICICI info|Dear Customer, Acct XX123 is debited with INR 350.00 on 14-Oct-26. Info: UPI/328912/Uber India. Available Bal: INR 8,920.00.|EXPENSE|35000|2026-10-14|Uber India|null|123
            ICICI card|INR 2,450.00 spent on ICICI Bank Card XX9012 on 14-Oct-26 at ZOMATO. Avl Lmt: INR 85,000.00. To dispute, call 18001080.|EXPENSE|245000|2026-10-14|Zomato|ICICI Bank|9012
            SBI UPI|Dear UPI user A/C X1234 debited by 1500.0 on date 14Oct26 trf to RAJESH KUMAR Refno 428901234567. If not u? call 1800111109. -SBI|EXPENSE|150000|2026-10-14|Rajesh Kumar|SBI|1234
            SBI credit|Your A/C XXXXX12345 Credited INR 5,000.00 on 13/10/26 -Deposit by transfer from NEHA SHARMA. Avl Bal INR 45,210.00-SBI|INCOME|500000|2026-10-13|Neha Sharma|SBI|2345
            Axis|INR 620.00 debited from A/c no. XX3456 on 14-10-2026 14:32:10 towards ZEPTO. Avl Bal: INR 15,200.50 - Axis Bank|EXPENSE|62000|2026-10-14|Zepto|Axis Bank|3456
            Kotak|Sent Rs.199.00 from Kotak Bank AC X7890 to spotify@ybl on 12-10-26.UPI Ref 428912. Not you, kotak.com/fraud|EXPENSE|19900|2026-10-12|Spotify|Kotak|7890
            ATM|Rs.3000 withdrawn at ATM from A/c XX1234 on 04-10-26. Avl Bal Rs.40,010.20|EXPENSE|300000|2026-10-04||null|1234
            Refund|Refund of Rs 499.00 credited to your HDFC Bank Credit Card ending 5678 from MYNTRA on 10-10-26.|INCOME|49900|2026-10-10|Myntra|HDFC Bank|5678
            """)
    void readsCommonAlerts(String name, String text, String kind, long amount, String on, String merchant, String bank, String account) {
        AlertParser.Parsed p = AlertParser.parse(text, null, RECEIVED);
        assertThat(p).as(name).isNotNull();
        assertThat(p.kind()).as(name).isEqualTo(kind);
        assertThat(p.amount()).as(name).isEqualTo(amount);
        assertThat(p.on()).as(name).isEqualTo(LocalDate.parse(on));
        if (merchant != null) {
            assertThat(p.merchant()).as(name).isEqualTo(merchant);
        }
        assertThat(p.bank()).as(name).isEqualTo("null".equals(bank) ? null : bank);
        assertThat(p.accountHint()).as(name).isEqualTo(account);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            123456 is your OTP for a transaction of Rs.4,500.00 at AMAZON. Do not share it with anyone. - HDFC Bank
            Your credit card bill of Rs 12,345 is due on 20-10-26. Pay now to avoid charges.
            Rs.2,000.00 will be debited from your a/c XX1234 on 20-10-26 towards SIP mandate.
            Get a pre-approved loan of Rs 5,00,000 at 10.5%! Apply now.
            Txn of Rs 450 at Swiggy declined due to insufficient balance.
            Hi! Your order is out for delivery.
            """)
    void leavesOtherMessagesAlone(String text) {
        assertThat(AlertParser.parse(text, null, RECEIVED)).isNull();
    }

    @Test
    void theBalanceIsNeverTakenForTheAmount() {
        var p = AlertParser.parse("Avl Bal Rs 50,000.00. Your a/c XX1234 debited with Rs 120.00 on 14-10-26 at Chai Point.", null, RECEIVED);
        assertThat(p.amount()).isEqualTo(12_000);
        assertThat(p.balance()).isEqualTo(5_000_000);
    }

    @Test
    void anEmailsHtmlBecomesText() {
        var p = AlertParser.parse("<html><body><p>Dear Customer,</p><p>Rs.&nbsp;799.00 has been debited from account <b>**4321</b> to VPA "
                + "netflix@hdfcbank on 15-10-26.</p><style>p{}</style></body></html>", "alerts@hdfcbank.net", RECEIVED);
        assertThat(p.amount()).isEqualTo(79_900);
        assertThat(p.merchant()).isEqualTo("Netflix");
        assertThat(p.bank()).isEqualTo("HDFC Bank");
        assertThat(p.accountHint()).isEqualTo("4321");
    }

    @Test
    void aPasteOfManyMessagesSplitsIntoOneEach() {
        String paste = """
                Rs.450.00 debited from a/c **1234 on 14-10-26 to VPA swiggy@icici.
                Rs.120.00 debited from a/c **1234 on 14-10-26 to VPA chaipoint@ybl.

                Your A/C XXXXX12345 Credited INR 5,000.00 on 13/10/26 by NEHA SHARMA.
                Avl Bal INR 45,210.00
                """;
        assertThat(AlertParser.split(paste)).hasSize(3);
    }

    @Test
    void withoutADateItIsTheDayItArrived() {
        assertThat(AlertParser.parse("Rs 99 debited from your a/c XX1234 to Blinkit.", null, RECEIVED).on()).isEqualTo(RECEIVED);
    }
}
