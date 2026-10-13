package dev.payledger.core.money;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ImportParserTest {

    @Test
    void amountsAreReadTheIndianWay() {
        assertThat(ImportParser.amount("₹1,00,000.50")).contains(10_000_050L);
        assertThat(ImportParser.amount("1,00,000")).contains(10_000_000L);
        assertThat(ImportParser.amount("100,000")).contains(10_000_000L);
        assertThat(ImportParser.amount("Rs. 486")).contains(48_600L);
        assertThat(ImportParser.amount("INR 12.5")).contains(1_250L);
        assertThat(ImportParser.amount("(500)")).contains(-50_000L);
        assertThat(ImportParser.amount("-₹1,200.75")).contains(-120_075L);
        assertThat(ImportParser.amount("12abc")).isEmpty();
        assertThat(ImportParser.amount("1.234")).isEmpty();
        assertThat(ImportParser.amount("")).isEmpty();
    }

    @Test
    void datesAreDayFirst() {
        LocalDate fifthOct = LocalDate.of(2026, 10, 5);
        for (String text : List.of("2026-10-05", "05-10-2026", "5/10/2026", "05.10.2026", "05/10/26", "05-10-26",
                "05-Oct-2026", "5 Oct 2026", "05-OCT-26", "05/Oct/2026")) {
            assertThat(ImportParser.date(text)).as(text).contains(fifthOct);
        }
        assertThat(ImportParser.date("31/02/2026")).isEmpty();
        assertThat(ImportParser.date("10/05")).isEmpty();
        assertThat(ImportParser.date("yesterday")).isEmpty();
    }

    @Test
    void csvHandlesQuotesCommasLineBreaksAndExcelsByteOrderMark() {
        String text = "﻿Date,Amount,Description,Payment Mode\r\n"
                + "05-10-2026,\"1,00,000\",\"Rent, October\",UPI\r\n"
                + "\r\n"
                + "06-10-2026,50,\"Said \"\"thanks\"\"\nand left\",cash";
        List<ImportParser.Line> lines = ImportParser.csv(text);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).number()).isEqualTo(2);
        assertThat(lines.get(0).get("amount")).isEqualTo("1,00,000");
        assertThat(lines.get(0).get("description")).isEqualTo("Rent, October");
        assertThat(lines.get(0).get("payment_mode")).isEqualTo("UPI");
        assertThat(lines.get(1).number()).isEqualTo(4);
        assertThat(lines.get(1).get("description")).isEqualTo("Said \"thanks\"\nand left");
    }

    @Test
    void bankColumnNamesMapToTheTemplates() {
        assertThat(ImportParser.column("Narration")).isEqualTo("description");
        assertThat(ImportParser.column(" Withdrawal Amount ")).isEqualTo("debit");
        assertThat(ImportParser.column("Dr")).isEqualTo("debit");
        assertThat(ImportParser.column("Deposit")).isEqualTo("credit");
        assertThat(ImportParser.column("Txn Date")).isEqualTo("date");
        assertThat(ImportParser.column("Ref")).isEqualTo("external_id");
        assertThat(ImportParser.column("To Account")).isEqualTo("to_account");
        assertThat(Optional.of(ImportParser.column("Amount"))).contains("amount");
    }
}
