package dev.payledger.core.money;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads the import template (product plan 2.5): CSV text into rows of named cells, and the Indian ways of
 * writing dates and amounts into values. Pure functions, so every format is unit-tested.
 */
final class ImportParser {

    /** Other names banks and spreadsheets use for the template's columns. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("txn_date", "date"), Map.entry("transaction_date", "date"), Map.entry("value_date", "date"),
            Map.entry("narration", "description"), Map.entry("particulars", "description"), Map.entry("remarks", "description"),
            Map.entry("dr", "debit"), Map.entry("withdrawal", "debit"), Map.entry("withdrawal_amount", "debit"),
            Map.entry("cr", "credit"), Map.entry("deposit", "credit"), Map.entry("deposit_amount", "credit"),
            Map.entry("note", "notes"), Map.entry("tag", "tags"), Map.entry("ref", "external_id"),
            Map.entry("reference", "external_id"), Map.entry("transaction_id", "external_id"));

    private static final Pattern AMOUNT = Pattern.compile("\\d+(\\.\\d{1,2})?");
    private static final List<DateTimeFormatter> DATES = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            pattern("d-M-uuuu"), pattern("d/M/uuuu"), pattern("d.M.uuuu"),
            twoDigitYear("d-M-"), twoDigitYear("d/M/"),
            pattern("d-MMM-uuuu"), pattern("d MMM uuuu"), pattern("d/MMM/uuuu"), twoDigitYear("d-MMM-"));

    private ImportParser() {
    }

    /** One data row: its line number in the file (the header is line 1) and its cells by column name. */
    record Line(int number, Map<String, String> cells) {

        String get(String column) {
            String value = cells.get(column);
            return value == null ? "" : value.strip();
        }
    }

    /**
     * RFC 4180 CSV: quoted cells may hold commas, quotes ("") and line breaks. A byte-order mark (Excel's
     * "CSV UTF-8") is ignored, and blank lines are skipped. Column names are matched loosely: case, spaces
     * and the aliases above don't matter.
     */
    static List<Line> csv(String text) {
        List<List<String>> records = records(text.startsWith("﻿") ? text.substring(1) : text);
        if (records.isEmpty()) {
            return List.of();
        }
        List<String> header = records.getFirst().stream().map(ImportParser::column).toList();
        List<Line> lines = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> record = records.get(i);
            if (record.stream().allMatch(String::isBlank)) {
                continue;
            }
            Map<String, String> cells = new LinkedHashMap<>();
            for (int c = 0; c < header.size() && c < record.size(); c++) {
                cells.putIfAbsent(header.get(c), record.get(c));
            }
            lines.add(new Line(i + 1, cells));
        }
        return lines;
    }

    /** "Payment Mode" -> payment_mode, "Dr" -> debit. */
    static String column(String name) {
        String key = name.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        return ALIASES.getOrDefault(key, key);
    }

    private static List<List<String>> records(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
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
            } else if (ch == '"' && cell.isEmpty()) {
                quoted = true;
            } else if (ch == ',') {
                record.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                record.add(cell.toString());
                cell.setLength(0);
                records.add(record);
                record = new ArrayList<>();
            } else {
                cell.append(ch);
            }
        }
        if (!cell.isEmpty() || !record.isEmpty()) {
            record.add(cell.toString());
            records.add(record);
        }
        return records;
    }

    /**
     * Paise from "₹1,00,000.50", "1,00,000", "Rs. 500", "-500" or "(500)" (accountants' negative).
     * Commas are only grouping, in lakh/crore or thousands style. Empty when it isn't an amount.
     */
    static Optional<Long> amount(String text) {
        String s = text.strip();
        boolean negative = false;
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true;
            s = s.substring(1, s.length() - 1).strip();
        }
        s = s.replaceFirst("(?i)^(₹|rs\\.?|inr)\\s*", "");
        if (s.startsWith("-")) {
            negative = !negative;
            s = s.substring(1).strip();
        }
        s = s.replaceFirst("(?i)^(₹|rs\\.?|inr)\\s*", "").replace(",", "");
        if (!AMOUNT.matcher(s).matches()) {
            return Optional.empty();
        }
        String[] parts = s.split("\\.");
        long paise = Long.parseLong(parts[0]) * 100;
        if (parts.length == 2) {
            paise += Long.parseLong((parts[1] + "0").substring(0, 2));
        }
        return Optional.of(negative ? -paise : paise);
    }

    /** ISO, DD-MM-YYYY, DD/MM/YYYY, DD.MM.YYYY, DD/MM/YY, 05-Oct-2026, 5 Oct 2026. Day first, always. */
    static Optional<LocalDate> date(String text) {
        String s = text.strip();
        for (DateTimeFormatter format : DATES) {
            try {
                return Optional.of(LocalDate.parse(s, format));
            } catch (DateTimeException e) {
                // try the next format
            }
        }
        return Optional.empty();
    }

    private static DateTimeFormatter pattern(String pattern) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern)
                .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);
    }

    /** "05/10/26" is 2026: two-digit years are this century. */
    private static DateTimeFormatter twoDigitYear(String prefix) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(prefix)
                .appendValueReduced(java.time.temporal.ChronoField.YEAR, 2, 2, 2000)
                .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);
    }
}
