package dev.payledger.core.capture;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** HDFC's alert email as it looks in October 2026 (anonymised): read as the debit it is, with the payee's name. */
class HdfcEmailTest {

    @Test
    void hdfcsNewUpiAlertEmailIsADebitToThePayeeInBrackets() throws Exception {
        MimeMessage mail;
        try (InputStream in = getClass().getResourceAsStream("/capture/hdfc-upi-2026.eml")) {
            mail = new MimeMessage(Session.getInstance(new Properties()), in);
        }
        String text = (mail.getSubject() + "\n") + AlertParser.clean(CaptureService.text(mail));
        assertThat(AlertParser.noise(text)).isFalse();
        AlertParser.Parsed p = AlertParser.parse(text, "alerts@hdfcbank.bank.in", LocalDate.of(2026, 10, 15));
        assertThat(p).isNotNull();
        assertThat(p.kind()).isEqualTo("EXPENSE");
        assertThat(p.amount()).isEqualTo(1_000_000L);
        // The payee in brackets, not a phrase from the sign-off ("to support you in every step of the way").
        assertThat(p.merchant()).isEqualTo("Asha K");
        assertThat(p.bank()).isEqualTo("HDFC Bank");
        assertThat(p.accountHint()).isEqualTo("1234");
    }

    @Test
    void aDeclinedCardChargeIsNotATransaction() {
        assertThat(AlertParser.noise("We regret to inform you that a transaction of Rs. 102.69 on your HDFC Bank Credit Card ending 2365 "
                + "at ORACLE SINGAPORE was declined.")).isTrue();
    }
}
