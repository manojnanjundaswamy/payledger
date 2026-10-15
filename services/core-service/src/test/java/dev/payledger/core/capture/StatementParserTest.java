package dev.payledger.core.capture;

import dev.payledger.core.common.InvalidRequestException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Statements in each big bank's own layout (names and numbers made up): the rows, which way, and that they add up. */
class StatementParserTest {

    @Test
    void hdfcCsvWithItsPreamble() {
        String csv = """
                HDFC BANK Ltd.,,,,,,
                Statement of account,,,,,,
                Account No :,50100123451234,,,,,
                ,,,,,,
                Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance
                01/10/26,NEFT CR-ACME SOFTWARE PVT LTD-SALARY OCT,N274264,01/10/26,,"1,20,000.00","1,25,000.00"
                02/10/26,UPI-SWIGGY-swiggy@icici-ICIC0DC0099-428910283746-PAYMENT,0000428910283746,02/10/26,450.00,,"1,24,550.00"
                03/10/26,ATW-512345XXXXXX1234-S1ANBG12-BANGALORE,0000001234,03/10/26,"3,000.00",,"1,21,550.00"
                """;
        StatementParser.Statement s = StatementParser.parse(csv.getBytes(StandardCharsets.UTF_8), "Acct_Statement.csv", null);
        assertThat(s.bank()).isEqualTo("HDFC Bank");
        assertThat(s.lines()).extracting(StatementParser.Line::in).containsExactly(12_000_000L, 0L, 0L);
        assertThat(s.lines()).extracting(StatementParser.Line::out).containsExactly(0L, 45_000L, 300_000L);
        assertThat(s.lines().getFirst().date()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(s.opening()).isEqualTo(500_000);
        assertThat(s.adds()).isTrue();
        assertThat(StatementParser.merchant(s.lines().get(1).narration())).isEqualTo("Swiggy");
        assertThat(StatementParser.merchant(s.lines().get(0).narration())).isEqualTo("Acme Software");
    }

    @Test
    void iciciExcelPrefersTheTransactionDateOverTheValueDate() throws Exception {
        byte[] xlsx;
        try (var wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("OpTransactionHistory");
            String[][] rows = {
                    {"ICICI Bank detailed statement"}, {},
                    {"S No.", "Value Date", "Transaction Date", "Cheque Number", "Transaction Remarks", "Withdrawal Amount (INR )", "Deposit Amount (INR )", "Balance (INR )"},
                    {"1", "06/10/2026", "05/10/2026", "-", "UPI/428912345678/Payment from Ph/zomato@hdfcbank/ZOMATO LTD", "650.00", "0.00", "9,350.00"},
                    {"2", "07/10/2026", "07/10/2026", "-", "IMPS/P2A/428900011122/RAHUL SHARMA/Refund", "0.00", "500.00", "9,850.00"},
            };
            for (int r = 0; r < rows.length; r++) {
                var row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            wb.write(out);
            xlsx = out.toByteArray();
        }
        var s = StatementParser.parse(xlsx, "statement.xlsx", null);
        assertThat(s.bank()).isEqualTo("ICICI Bank");
        assertThat(s.lines()).extracting(StatementParser.Line::date).containsExactly(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 7));
        assertThat(s.lines()).extracting(StatementParser.Line::out).containsExactly(65_000L, 0L);
        assertThat(s.adds()).isTrue();
        assertThat(StatementParser.merchant(s.lines().get(1).narration())).isEqualTo("Rahul Sharma");
    }

    @Test
    void sbiTabSeparatedNewestFirst() {
        String tsv = String.join("\n",
                "Account Name\t:\tMr. Manoj",
                "Txn Date\tValue Date\tDescription\tRef No./Cheque No.\tDebit\tCredit\tBalance",
                "12 Oct 2026\t12 Oct 2026\tTO TRANSFER-UPI/DR/428901234567/BESCOM/YESB/bescom@ybl/Payment\tTRANSFER TO 4897\t1,963.00\t\t38,037.00",
                "11 Oct 2026\t11 Oct 2026\tBY TRANSFER-NEFT*HDFC0000001*N284*NEHA SHARMA\tTRANSFER FROM 3199\t\t5,000.00\t40,000.00",
                "");
        var s = StatementParser.parse(tsv.getBytes(StandardCharsets.UTF_8), "sbi.txt", null);
        // Put in date order, whatever order the bank wrote them in.
        assertThat(s.lines()).extracting(StatementParser.Line::date).containsExactly(LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 12));
        assertThat(s.lines().getFirst().in()).isEqualTo(500_000);
        assertThat(s.opening()).isEqualTo(3_500_000);
        assertThat(s.adds()).isTrue();
        assertThat(StatementParser.merchant(s.lines().get(1).narration())).isEqualTo("BESCOM");
    }

    @Test
    void axisShortColumnNames() {
        String csv = """
                Tran Date,CHQNO,PARTICULARS,DR,CR,BAL,SOL
                14-10-2026,,UPI/P2M/428912/ZEPTO MARKETPLACE/zepto@ybl/Payment,620.00,,"15,200.50",4512
                """;
        var s = StatementParser.parse(csv.getBytes(StandardCharsets.UTF_8), "axis.csv", null);
        assertThat(s.lines()).singleElement().satisfies(l -> {
            assertThat(l.out()).isEqualTo(62_000);
            assertThat(l.balance()).isEqualTo(1_520_050);
        });
        assertThat(StatementParser.merchant(s.lines().getFirst().narration())).isEqualTo("Zepto Marketplace");
    }

    @Test
    void kotakOneAmountWithDrCr() {
        String csv = """
                Sl. No.,Transaction Date,Value Date,Description,Chq / Ref No.,Amount,Dr / Cr,Balance,Dr / Cr
                1,12-10-2026,12-10-2026,UPI/SPOTIFY/428912/Payment,UPI-428912,199.00,DR,"9,801.00",CR
                2,13-10-2026,13-10-2026,Int.Pd:1234:01-07-2026 to 30-09-2026,,52.00,CR,"9,853.00",CR
                """;
        var s = StatementParser.parse(csv.getBytes(StandardCharsets.UTF_8), "kotak.csv", null);
        assertThat(s.lines()).extracting(StatementParser.Line::out).containsExactly(19_900L, 0L);
        assertThat(s.lines()).extracting(StatementParser.Line::in).containsExactly(0L, 5_200L);
        assertThat(s.adds()).isTrue();
    }

    @Test
    void aPdfReadsItsRowsAndTheBalanceSaysWhichWay() throws Exception {
        List<String> text = List.of("HDFC BANK", "Statement of account", "Opening Balance 10,000.00",
                "Date Narration Ref Withdrawal Deposit Balance",
                "01/10/26 UPI-BIGBASKET-bigbasket@hdfcbank 4289 1,250.00 8,750.00",
                "02/10/26 NEFT CR-ACME SOFTWARE PVT LTD 1,20,000.00 1,28,750.00",
                "SALARY FOR OCTOBER",
                "03/10/26 POS 4512 DECATHLON SPORTS 2,479.00 1,26,271.00");
        var s = StatementParser.parse(pdf(text, null), "statement.pdf", null);
        assertThat(s.lines()).extracting(StatementParser.Line::out).containsExactly(125_000L, 0L, 247_900L);
        assertThat(s.lines().get(1).in()).isEqualTo(12_000_000);
        assertThat(s.lines().get(1).narration()).contains("SALARY FOR OCTOBER");
        assertThat(s.adds()).isTrue();

        // Protected: asks for the password, refuses a wrong one, opens with the right one.
        byte[] locked = pdf(text, "01011990");
        assertThatThrownBy(() -> StatementParser.parse(locked, "e.pdf", null)).isInstanceOf(InvalidRequestException.class)
                .extracting("code").isEqualTo("statement-password");
        assertThatThrownBy(() -> StatementParser.parse(locked, "e.pdf", "nope")).extracting("code").isEqualTo("statement-wrong-password");
        assertThat(StatementParser.parse(locked, "e.pdf", "01011990").lines()).hasSize(3);
    }

    @Test
    void aFileWithoutTheColumnsSaysSo() {
        assertThatThrownBy(() -> StatementParser.parse("a,b,c\n1,2,3\n".getBytes(StandardCharsets.UTF_8), "x.csv", null))
                .extracting("code").isEqualTo("statement-unreadable");
    }

    private static byte[] pdf(List<String> lines, String password) throws Exception {
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var page = new PDPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.setLeading(14);
                cs.newLineAtOffset(40, 740);
                for (String l : lines) {
                    cs.showText(l);
                    cs.newLine();
                }
                cs.endText();
            }
            if (password != null) {
                var policy = new StandardProtectionPolicy("owner-" + password, password, new AccessPermission());
                policy.setEncryptionKeyLength(128);
                doc.protect(policy);
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
