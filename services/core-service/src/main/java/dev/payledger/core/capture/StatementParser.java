package dev.payledger.core.capture;

import dev.payledger.core.common.InvalidRequestException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a bank statement (CSV, Excel or PDF) into its rows: date, narration, money out, money in, balance. Banks name
 * their columns differently ("Narration", "Particulars", "Transaction Remarks"; "Withdrawal Amt.", "Debit", "DR"), so
 * the header row is found by what it contains, not by a template per bank. In a PDF, where columns are just text, a
 * row is a line that starts with a date and ends with amounts, and whether money came in or went out is read from
 * the change in balance. The password of a protected PDF is used once and never kept.
 */
public final class StatementParser {

    private StatementParser() {
    }

    /** @param out paise (0 when money came in); @param in paise; @param balance paise after the row, or null */
    public record Line(LocalDate date, String narration, long out, long in, Long balance) {
    }

    /**
     * @param opening balance before the first row, when the statement says (or it can be worked out)
     * @param adds    opening + money in − money out = the last balance, when both are known; null when it can't be checked
     */
    public record Statement(String bank, List<Line> lines, Long opening, Long closing, Boolean adds) {
    }

    static final int MAX_ROWS = 5000;

    public static Statement parse(byte[] file, String filename, String password) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf") || startsWith(file, "%PDF")) {
            return pdf(file, password);
        }
        if (name.endsWith(".xlsx") || name.endsWith(".xls") || startsWith(file, "PK") || startsWith(file, "ÐÏ")) {
            return table(sheet(file));
        }
        return table(csv(new String(file, StandardCharsets.UTF_8).replace("﻿", "")));
    }

    // ---------------------------------------------------------------- tables (CSV, Excel)

    private enum Col { DATE, NARRATION, OUT, IN, AMOUNT, DRCR, BALANCE }

    private static final Map<Col, Pattern> HEADERS = Map.of(
            Col.DATE, Pattern.compile("^(txn|tran|transaction|value|posting)?\\s*\\.?\\s*date|^date$", Pattern.CASE_INSENSITIVE),
            Col.NARRATION, Pattern.compile("narration|description|particulars|remarks|details", Pattern.CASE_INSENSITIVE),
            Col.OUT, Pattern.compile("withdrawal|debit(?!\\s*/)|^dr\\.?$|paid out|money out", Pattern.CASE_INSENSITIVE),
            Col.IN, Pattern.compile("deposit|credit(?!\\s*/)|^cr\\.?$|paid in|money in", Pattern.CASE_INSENSITIVE),
            Col.AMOUNT, Pattern.compile("^(transaction\\s+)?amount", Pattern.CASE_INSENSITIVE),
            Col.DRCR, Pattern.compile("dr\\s*/\\s*cr|debit\\s*/\\s*credit|^type$|cr/dr", Pattern.CASE_INSENSITIVE),
            Col.BALANCE, Pattern.compile("balance|^bal\\.?$", Pattern.CASE_INSENSITIVE));

    static Statement table(List<List<String>> rows) {
        int header = -1;
        Map<Col, Integer> at = new java.util.EnumMap<>(Col.class);
        String bank = null;
        for (int r = 0; r < Math.min(rows.size(), 60) && header < 0; r++) {
            List<String> row = rows.get(r);
            bank = bank != null ? bank : bankIn(String.join(" ", row));
            Map<Col, Integer> found = new java.util.EnumMap<>(Col.class);
            boolean dateIsValueDate = false;
            for (int c = 0; c < row.size(); c++) {
                String cell = row.get(c).strip();
                for (Col col : Col.values()) {
                    if (!HEADERS.get(col).matcher(cell).find()) {
                        continue;
                    }
                    boolean valueDate = col == Col.DATE && cell.toLowerCase(Locale.ROOT).contains("value");
                    if (!found.containsKey(col)) {
                        found.put(col, c);
                        dateIsValueDate = valueDate || col != Col.DATE && dateIsValueDate;
                    } else if (col == Col.DATE && dateIsValueDate && !valueDate) {
                        // The transaction date, not the value date, when there are both.
                        found.put(col, c);
                        dateIsValueDate = false;
                    }
                    break;
                }
            }
            if (found.containsKey(Col.DATE) && found.containsKey(Col.NARRATION)
                    && (found.containsKey(Col.OUT) && found.containsKey(Col.IN) || found.containsKey(Col.AMOUNT))) {
                header = r;
                at = found;
            }
        }
        if (header < 0) {
            throw new InvalidRequestException("statement-unreadable",
                    "couldn't find the columns (date, description, debit/credit or amount); download the statement as CSV or Excel from your bank's net banking");
        }
        List<Line> lines = new ArrayList<>();
        for (int r = header + 1; r < rows.size() && lines.size() < MAX_ROWS; r++) {
            List<String> row = rows.get(r);
            LocalDate date = date(cell(row, at.get(Col.DATE)));
            if (date == null) {
                // A narration that runs onto the next row belongs to the row above.
                String more = cell(row, at.get(Col.NARRATION)).strip();
                if (!more.isEmpty() && !lines.isEmpty() && cell(row, at.get(Col.BALANCE)).isBlank()) {
                    Line last = lines.removeLast();
                    lines.add(new Line(last.date(), (last.narration() + " " + more).strip(), last.out(), last.in(), last.balance()));
                }
                continue;
            }
            long out;
            long in;
            if (at.containsKey(Col.OUT) && at.containsKey(Col.IN)) {
                out = money(cell(row, at.get(Col.OUT)));
                in = money(cell(row, at.get(Col.IN)));
            } else {
                // One amount column: a Dr/Cr column says which way, else a Cr/Dr on the amount, else its sign.
                String raw = cell(row, at.get(Col.AMOUNT));
                long amount = money(raw);
                String type = (at.containsKey(Col.DRCR) ? cell(row, at.get(Col.DRCR)) : raw).toUpperCase(Locale.ROOT);
                boolean credit = type.matches(".*\\b(CR|CREDIT|C)\\b.*") ? true
                        : type.matches(".*\\b(DR|DEBIT|D)\\b.*") ? false
                        : !(raw.strip().startsWith("-") || raw.contains("("));
                out = credit ? 0 : amount;
                in = credit ? amount : 0;
            }
            if (out == 0 && in == 0) {
                continue;
            }
            String bal = at.containsKey(Col.BALANCE) ? cell(row, at.get(Col.BALANCE)) : "";
            Long balance = bal.isBlank() ? null : signedBalance(bal);
            lines.add(new Line(date, cell(row, at.get(Col.NARRATION)).replaceAll("\\s+", " ").strip(), Math.abs(out), Math.abs(in), balance));
        }
        return finish(bank, lines, null);
    }

    static List<List<String>> csv(String text) {
        List<List<String>> rows = new ArrayList<>();
        char sep = text.lines().limit(30).anyMatch(l -> l.chars().filter(ch -> ch == '\t').count() > 3) ? '\t'
                : text.lines().limit(30).anyMatch(l -> l.chars().filter(ch -> ch == ';').count() > 3 && !l.contains(",")) ? ';' : ',';
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == sep) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(ch);
            }
        }
        if (!cell.isEmpty() || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    static List<List<String>> sheet(byte[] file) {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(file))) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter(Locale.ENGLISH);
            DateTimeFormatter iso = DateTimeFormatter.ISO_LOCAL_DATE;
            List<List<String>> rows = new ArrayList<>();
            for (Row r : sheet) {
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < Math.max(0, r.getLastCellNum()); c++) {
                    Cell cell = r.getCell(c);
                    if (cell == null) {
                        cells.add("");
                    } else if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                        cells.add(cell.getLocalDateTimeCellValue().toLocalDate().format(iso));
                    } else if (cell.getCellType() == CellType.NUMERIC) {
                        cells.add(BigDecimal.valueOf(cell.getNumericCellValue()).toPlainString());
                    } else {
                        cells.add(fmt.formatCellValue(cell));
                    }
                }
                rows.add(cells);
            }
            return rows;
        } catch (Exception e) {
            throw new InvalidRequestException("statement-unreadable", "couldn't open the spreadsheet; save it as .xlsx or CSV and try again");
        }
    }

    // ---------------------------------------------------------------- PDF

    private static final String DATE_RX = "(\\d{1,2}[-/. ](?:\\d{1,2}|[A-Za-z]{3})[-/. ]\\d{2,4})";
    private static final String MONEY_RX = "(-?[0-9][0-9,]*\\.\\d{2})";
    /** A row: date (and maybe a value date), narration, then one or two amounts and the balance, maybe with Cr/Dr. */
    private static final Pattern PDF_ROW = Pattern.compile("^\\s*" + DATE_RX + "\\s+(?:" + DATE_RX + "\\s+)?(.*?)\\s+" + MONEY_RX
            + "(?:\\s+" + MONEY_RX + ")?\\s+" + MONEY_RX + "\\s*(Cr|Dr|CR|DR)?\\s*$");
    private static final Pattern OPENING = Pattern.compile("(?i)opening\\s+balance[^0-9-]*" + MONEY_RX + "\\s*(Cr|Dr)?");

    static Statement pdf(byte[] file, String password) {
        String text;
        try (PDDocument doc = Loader.loadPDF(file, password == null ? "" : password)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            text = stripper.getText(doc);
        } catch (InvalidPasswordException e) {
            throw new InvalidRequestException(password == null || password.isEmpty() ? "statement-password" : "statement-wrong-password",
                    password == null || password.isEmpty() ? "this PDF is protected: enter its password (often your date of birth or part of your customer id)"
                            : "that password didn't open the PDF");
        } catch (Exception e) {
            throw new InvalidRequestException("statement-unreadable", "couldn't read the PDF; try the CSV or Excel download instead");
        }
        String bank = bankIn(text.length() > 3000 ? text.substring(0, 3000) : text);
        Long opening = null;
        Matcher o = OPENING.matcher(text);
        if (o.find()) {
            opening = signed(o.group(1), o.group(2));
        }
        record Raw(LocalDate date, String narration, long a, Long b, long balance) {
        }
        List<Raw> raws = new ArrayList<>();
        for (String line : text.split("\\R")) {
            Matcher m = PDF_ROW.matcher(line);
            if (m.matches()) {
                LocalDate date = date(m.group(1));
                if (date == null) {
                    continue;
                }
                long first = money(m.group(4));
                Long second = m.group(5) == null ? null : money(m.group(5));
                raws.add(new Raw(date, m.group(3).strip(), first, second, signed(m.group(6), m.group(7))));
            } else if (!raws.isEmpty() && !line.isBlank() && !line.matches("(?i).*(page \\d|statement|balance|total).*") && line.length() < 120) {
                Raw last = raws.removeLast();
                raws.add(new Raw(last.date(), (last.narration() + " " + line.strip()).strip(), last.a(), last.b(), last.balance()));
            }
            if (raws.size() >= MAX_ROWS) {
                break;
            }
        }
        if (raws.isEmpty()) {
            throw new InvalidRequestException("statement-unreadable",
                    "no transactions found in the PDF (a scanned statement has no text); download the CSV or Excel statement instead");
        }
        List<Line> lines = new ArrayList<>();
        Long previous = opening;
        for (Raw r : raws) {
            long amount = r.a();
            Boolean in = null;
            if (r.b() != null) {
                // Both columns printed, one of them 0.00: that says which way.
                if (r.a() == 0) {
                    amount = r.b();
                    in = true;
                } else if (r.b() == 0) {
                    in = false;
                }
            }
            if (in == null && previous != null) {
                // Otherwise the balance does.
                long change = r.balance() - previous;
                in = change == amount ? Boolean.TRUE : change == -amount ? Boolean.FALSE : change > 0;
            }
            if (in == null) {
                in = r.narration().toUpperCase(Locale.ROOT).matches(".*\\b(CR|CREDIT|DEPOSIT|SALARY|REFUND|INTEREST|NEFT CR|IMPS CR)\\b.*");
            }
            lines.add(new Line(r.date(), r.narration().replaceAll("\\s+", " "), in ? 0 : amount, in ? amount : 0, r.balance()));
            previous = r.balance();
        }
        return finish(bank, lines, opening);
    }

    // ---------------------------------------------------------------- shared

    private static Statement finish(String bank, List<Line> lines, Long opening) {
        if (lines.isEmpty()) {
            throw new InvalidRequestException("statement-empty", "the statement has no transactions in it");
        }
        // Newest first in some banks' files: put them in date order (stable, so same-day order is kept).
        boolean descending = lines.size() > 1 && lines.getFirst().date().isAfter(lines.getLast().date());
        if (descending) {
            lines = new ArrayList<>(lines.reversed());
        }
        Line first = lines.getFirst();
        Long open = opening != null ? opening : first.balance() == null ? null : first.balance() + first.out() - first.in();
        Long closing = lines.getLast().balance();
        Boolean adds = null;
        if (open != null && closing != null && lines.stream().allMatch(l -> l.balance() != null)) {
            long sum = open + lines.stream().mapToLong(l -> l.in() - l.out()).sum();
            adds = sum == closing;
        }
        return new Statement(bank, lines, open, closing, adds);
    }

    private static String cell(List<String> row, Integer i) {
        return i == null || i >= row.size() || row.get(i) == null ? "" : row.get(i);
    }

    /** "1,20,000.50", "₹ 450", "(500.00)", "450.00 Dr" → paise (always positive; the column says which way). */
    static long money(String s) {
        String t = s == null ? "" : s.replaceAll("(?i)(rs\\.?|inr|₹|cr|dr|\\s)", "").replace(",", "").replace("(", "").replace(")", "").replace("+", "");
        if (t.isEmpty() || t.equals("-") || !t.matches("-?\\d+(\\.\\d+)?")) {
            return 0;
        }
        return Math.abs(new BigDecimal(t).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact());
    }

    /** A balance, negative when overdrawn ("-500.00", "500.00 Dr"). */
    private static long signedBalance(String s) {
        long v = money(s);
        return s.strip().startsWith("-") || s.toUpperCase(Locale.ROOT).contains("DR") ? -v : v;
    }

    private static long signed(String amount, String drcr) {
        long v = money(amount);
        return amount.strip().startsWith("-") || "DR".equalsIgnoreCase(drcr) ? -v : v;
    }

    private static final List<DateTimeFormatter> DATES = List.of(
            f("dd/MM/yy"), f("dd/MM/yyyy"), f("dd-MM-yyyy"), f("dd-MM-yy"), f("dd.MM.yyyy"), f("dd.MM.yy"), f("yyyy-MM-dd"),
            f("dd-MMM-yyyy"), f("dd-MMM-yy"), f("dd MMM yyyy"), f("dd MMM yy"), f("d MMM yyyy"), f("dd/MMM/yyyy"), f("d/M/yyyy"), f("d/M/yy"),
            f("MM/dd/yyyy"));

    static LocalDate date(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip().replaceAll("\\s+", " ");
        // Excel or a timestamp: keep the date part.
        if (t.matches("\\d{4}-\\d{2}-\\d{2}[T ].*")) {
            t = t.substring(0, 10);
        } else if (t.matches(".* \\d{1,2}:\\d{2}.*")) {
            t = t.replaceAll(" \\d{1,2}:\\d{2}.*$", "");
        }
        if (t.length() < 6 || t.length() > 12) {
            return null;
        }
        for (DateTimeFormatter f : DATES) {
            try {
                LocalDate d = LocalDate.parse(t, f);
                if (d.getYear() >= 2000 && d.getYear() <= 2100) {
                    return d;
                }
            } catch (DateTimeParseException ignored) {
                // next format
            }
        }
        return null;
    }

    private static DateTimeFormatter f(String p) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(p).toFormatter(Locale.ENGLISH);
    }

    private static String bankIn(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        for (String[] b : new String[][]{{"hdfc", "HDFC Bank"}, {"icici", "ICICI Bank"}, {"state bank", "SBI"}, {"sbi", "SBI"}, {"axis", "Axis Bank"},
                {"kotak", "Kotak"}, {"yes bank", "Yes Bank"}, {"idfc", "IDFC First"}, {"indusind", "IndusInd"}, {"federal", "Federal Bank"},
                {"bank of baroda", "Bank of Baroda"}, {"canara", "Canara Bank"}, {"punjab national", "PNB"}, {"union bank", "Union Bank"}}) {
            if (Pattern.compile("\\b" + b[0] + "\\b").matcher(t).find()) {
                return b[1];
            }
        }
        return null;
    }

    private static boolean startsWith(byte[] file, String prefix) {
        byte[] p = prefix.getBytes(StandardCharsets.ISO_8859_1);
        if (file.length < p.length) {
            return false;
        }
        for (int i = 0; i < p.length; i++) {
            if (file[i] != p[i]) {
                return false;
            }
        }
        return true;
    }

    private static final java.util.Set<String> NOISE = java.util.Set.of("UPI", "P2A", "P2M", "DR", "CR", "NEFT", "IMPS", "RTGS", "TO", "BY",
            "TRANSFER", "TRF", "INB", "MB", "NACH", "ACH", "ECS", "POS", "ATM", "ATW", "PAYMENT", "PAY", "PAYTM", "YESB", "HDFC", "ICIC", "SBIN",
            "UTIB", "KKBK", "BANK", "LTD", "PVT", "OTHER", "OTHERS", "INFO", "REF", "NO", "TXN", "ONLINE", "NA", "NULL", "CHQ", "CLG", "DEBIT",
            "CREDIT", "FROM", "FOR", "THE", "PH");

    /** Who a narration is with: "UPI-SWIGGY-swiggy@icici-…" → "Swiggy", "NEFT CR-ACME PVT LTD-…" → "Acme". */
    public static String merchant(String narration) {
        if (narration == null) {
            return null;
        }
        String n = narration.replaceAll("[A-Za-z0-9._]+@[A-Za-z]+", " ");
        for (String token : n.split("[/\\-|:*]+")) {
            String t = token.strip();
            // Skip refs, IFSC codes and parts made only of banking words ("TO TRANSFER", "NEFT CR", "UPI").
            if (t.length() < 3 || t.matches(".*\\d{4,}.*") || t.matches("(?i)[A-Z]{4}0[A-Z0-9]{6}")
                    || java.util.Arrays.stream(t.split("\\s+")).allMatch(w -> NOISE.contains(w.toUpperCase(Locale.ROOT).replace(".", "")))) {
                continue;
            }
            // "Acme Software Pvt Ltd" → "Acme Software".
            String tidy = AlertParser.tidy(t.replaceAll("(?i)\\s+(pvt|private)?\\.?\\s*(ltd|limited|llp|inc)\\.?$", ""));
            if (tidy != null) {
                return tidy;
            }
        }
        return null;
    }
}
