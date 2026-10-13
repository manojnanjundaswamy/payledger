package dev.payledger.core.money;

import dev.payledger.core.common.InvalidRequestException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The template as an Excel workbook (product plan 2.5): reading an uploaded .xlsx into the same rows a
 * CSV gives, and writing the template with dropdowns for type, account and category and an instructions tab.
 */
final class ImportXlsx {

    static final String SHEET = "Transactions";
    static final List<String> COLUMNS = List.of("date", "amount", "type", "account", "category", "subcategory", "description",
            "merchant", "payment_mode", "to_account", "tags", "notes", "currency", "external_id");
    /** Template rows that get dropdowns (an import holds at most 2,000 rows). */
    private static final int ROWS = 2_000;

    private ImportXlsx() {
    }

    /**
     * The sheet called Transactions, or else the first one. Dates Excel stores as numbers come back as
     * ISO dates, numbers as plain digits (no grouping, no exponent), formulas as their last value.
     */
    static List<ImportParser.Line> read(byte[] file) {
        try (Workbook book = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet sheet = book.getSheet(SHEET) != null ? book.getSheet(SHEET) : book.getSheetAt(0);
            DataFormatter text = new DataFormatter();
            List<String> header = new ArrayList<>();
            List<ImportParser.Line> lines = new ArrayList<>();
            for (Row row : sheet) {
                if (header.isEmpty()) {
                    for (Cell cell : row) {
                        while (header.size() < cell.getColumnIndex()) {
                            header.add("");
                        }
                        header.add(ImportParser.column(text.formatCellValue(cell)));
                    }
                    continue;
                }
                Map<String, String> cells = new LinkedHashMap<>();
                boolean blank = true;
                for (int c = 0; c < header.size(); c++) {
                    String value = value(row.getCell(c), text);
                    blank &= value.isBlank();
                    if (!header.get(c).isEmpty()) {
                        cells.putIfAbsent(header.get(c), value);
                    }
                }
                if (!blank) {
                    lines.add(new ImportParser.Line(row.getRowNum() + 1, cells));
                }
            }
            return lines;
        } catch (IOException | RuntimeException e) {
            // POI throws a variety of runtime exceptions for files that aren't real .xlsx workbooks.
            throw new InvalidRequestException("unreadable-file", "this isn't an Excel .xlsx file we can read; save it as .xlsx or CSV");
        }
    }

    private static String value(Cell cell, DataFormatter text) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue().toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
            }
            return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
        }
        if (type == CellType.STRING) {
            return cell.getRichStringCellValue().getString();
        }
        return text.formatCellValue(cell);
    }

    /**
     * The template: a Transactions sheet with this space's accounts and categories in its dropdowns, an
     * Instructions sheet, and a hidden Lists sheet the dropdowns read from (a list typed into a rule is
     * limited to 255 characters).
     */
    static byte[] template(List<String> accounts, List<String> categories, List<List<String>> examples) {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet(SHEET);
            Row header = sheet.createRow(0);
            var bold = book.createCellStyle();
            var font = book.createFont();
            font.setBold(true);
            bold.setFont(font);
            for (int c = 0; c < COLUMNS.size(); c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(COLUMNS.get(c));
                cell.setCellStyle(bold);
                sheet.setColumnWidth(c, 16 * 256);
            }
            for (int r = 0; r < examples.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<String> values = examples.get(r);
                for (int c = 0; c < values.size(); c++) {
                    row.createCell(c).setCellValue(values.get(c));
                }
            }
            sheet.createFreezePane(0, 1);

            Sheet lists = book.createSheet("Lists");
            fill(lists, 0, List.of("expense", "income", "transfer"));
            fill(lists, 1, accounts.isEmpty() ? List.of("HDFC savings") : accounts);
            fill(lists, 2, categories);
            fill(lists, 3, List.of("UPI", "cash", "card", "netbanking", "wallet"));
            book.setSheetHidden(book.getSheetIndex(lists), true);

            DataValidationHelper rules = sheet.getDataValidationHelper();
            dropdown(sheet, rules, COLUMNS.indexOf("type"), "Lists!$A$1:$A$3", false);
            // New account and category names are allowed: the import offers to create them.
            dropdown(sheet, rules, COLUMNS.indexOf("account"), "Lists!$B$1:$B$" + Math.max(1, accounts.size()), true);
            dropdown(sheet, rules, COLUMNS.indexOf("to_account"), "Lists!$B$1:$B$" + Math.max(1, accounts.size()), true);
            dropdown(sheet, rules, COLUMNS.indexOf("category"), "Lists!$C$1:$C$" + Math.max(1, categories.size()), true);
            dropdown(sheet, rules, COLUMNS.indexOf("payment_mode"), "Lists!$D$1:$D$5", true);

            Sheet help = book.createSheet("Instructions");
            String[] lines = {
                    "How to fill this in",
                    "",
                    "One row per transaction on the Transactions sheet. Needed: date, amount, type and account.",
                    "date: day first, like 05-10-2026 or 05/10/26 (an Excel date is fine too).",
                    "amount: a number like 1,00,000 or 486.50. Always positive; the type says which way it went.",
                    "type: expense, income or transfer. Left empty, a negative amount is an expense and a positive one income.",
                    "account: the account the money left (or arrived in, for income). Pick it from the list.",
                    "category: for expenses and income; empty means Other. A new name can be created when you import.",
                    "subcategory: optional, inside the category.",
                    "to_account: for a transfer, the account the money went to.",
                    "tags: optional, separated by ; (semicolons).",
                    "external_id: optional, your own reference (a bank's UTR). Rows with a reference you imported before are skipped.",
                    "",
                    "Importing the same file twice adds nothing, and every import can be undone in one step."
            };
            for (int i = 0; i < lines.length; i++) {
                help.createRow(i).createCell(0).setCellValue(lines[i]);
            }
            help.setColumnWidth(0, 120 * 256);
            book.setActiveSheet(0);
            book.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Exported transactions: real Excel dates and numbers, so the sheet sorts and sums as people expect. */
    static byte[] export(List<ExportService.Line> lines) {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet(SHEET);
            var bold = book.createCellStyle();
            var font = book.createFont();
            font.setBold(true);
            bold.setFont(font);
            var date = book.createCellStyle();
            date.setDataFormat(book.getCreationHelper().createDataFormat().getFormat("dd-mm-yyyy"));
            var money = book.createCellStyle();
            money.setDataFormat(book.getCreationHelper().createDataFormat().getFormat("#,##0.00"));
            Row header = sheet.createRow(0);
            for (int c = 0; c < COLUMNS.size(); c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(COLUMNS.get(c));
                cell.setCellStyle(bold);
                sheet.setColumnWidth(c, 16 * 256);
            }
            for (int r = 0; r < lines.size(); r++) {
                ExportService.Line line = lines.get(r);
                Row row = sheet.createRow(r + 1);
                List<String> cells = line.cells();
                for (int c = 0; c < cells.size(); c++) {
                    Cell cell = row.createCell(c);
                    if (c == 0) {
                        cell.setCellValue(line.date());
                        cell.setCellStyle(date);
                    } else if (c == 1) {
                        cell.setCellValue(line.amount().doubleValue());
                        cell.setCellStyle(money);
                    } else {
                        cell.setCellValue(cells.get(c));
                    }
                }
            }
            sheet.createFreezePane(0, 1);
            book.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void fill(Sheet sheet, int column, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            Row row = sheet.getRow(i) != null ? sheet.getRow(i) : sheet.createRow(i);
            row.createCell(column).setCellValue(values.get(i));
        }
    }

    private static void dropdown(Sheet sheet, DataValidationHelper rules, int column, String range, boolean allowOther) {
        DataValidation rule = rules.createValidation(rules.createFormulaListConstraint(range),
                new CellRangeAddressList(1, ROWS, column, column));
        rule.setShowErrorBox(!allowOther);
        if (!allowOther) {
            rule.setErrorStyle(DataValidation.ErrorStyle.STOP);
            rule.createErrorBox("Not in the list", "Pick a value from the list.");
        }
        sheet.addValidationData(rule);
    }
}
