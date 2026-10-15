package dev.payledger.core.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a model needs to read a transaction is the amount, the date, the words and the merchant. It never needs an
 * account or card number, a phone number, an email address, a reference number, a personal UPI id or the person's own
 * name, so those are replaced with placeholders before anything leaves our server. Amounts are left alone (a long run
 * of digits is masked only when it isn't money). We match accounts ourselves, from the unmasked text.
 */
public final class Masker {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");
    /** A UPI id: name@handle (no dot after the @, unlike an email). */
    private static final Pattern UPI = Pattern.compile("([A-Za-z0-9.*_-]{2,})@([A-Za-z]{2,20})\\b(?!\\.)");
    private static final Pattern PHONE = Pattern.compile("(?<![\\d.,])(?:\\+?91[\\s-]?)?[6-9]\\d{9}(?![\\d.,])|\\+\\d{1,3}[\\s-]?\\d{6,12}");
    /** Masked account or card numbers as banks write them: XX1234, **1234, xxxx-1234, ••1234. */
    private static final Pattern MASKED_NUMBER = Pattern.compile("(?:[Xx*•]{2,}[\\s-]?)+\\d{2,6}\\b");
    /** "account ending 0474", "A/c no. 1234", "card ending in 2365": the digits go, the words stay. */
    private static final Pattern ACCOUNT_ENDING = Pattern.compile(
            "(?i)\\b((?:a/c|acct|account|card)\\s*(?:no\\.?|number|ending(?:\\s+(?:with|in))?)?\\s*[:\\-]?\\s*)(\\d{3,6})\\b");
    /** A card number in groups, or any run of 9+ digits that isn't an amount (refs, account numbers). */
    private static final Pattern CARD = Pattern.compile("\\b\\d{4}[ -]\\d{4}[ -]\\d{4}[ -]\\d{1,7}\\b");
    private static final Pattern LONG_NUMBER = Pattern.compile("(?<![\\d,])\\d{9,}(?![\\d,]|\\.\\d)");
    /** Money just before a number: "Rs. ", "INR ", "₹", "$". */
    private static final Pattern CURRENCY_BEFORE = Pattern.compile("(?i)(?:rs\\.?|inr|usd|eur|gbp|aed|₹|\\$|€|£)\\s*$");

    private Masker() {
    }

    /** @param text the masked text; {@code masked} what kinds were hidden, for the log */
    public record Masked(String text, List<String> masked) {
    }

    /** @param ownName the person's display name (their own name becomes [YOU]), or null */
    public static Masked mask(String raw, String ownName) {
        if (raw == null) {
            return new Masked("", List.of());
        }
        List<String> kinds = new ArrayList<>();
        String s = replace(raw, EMAIL, "[EMAIL]", kinds, "email");
        s = maskUpi(s, kinds);
        s = replace(s, MASKED_NUMBER, "[ACCOUNT]", kinds, "account");
        s = replace(s, CARD, "[CARD]", kinds, "card");
        s = replace(s, PHONE, "[PHONE]", kinds, "phone");
        s = maskAccountEnding(s, kinds);
        s = maskLongNumbers(s, kinds);
        s = maskName(s, ownName, kinds);
        return new Masked(s, List.copyOf(kinds));
    }

    private static String replace(String s, Pattern p, String with, List<String> kinds, String kind) {
        Matcher m = p.matcher(s);
        if (!m.find()) {
            return s;
        }
        kinds.add(kind);
        return m.replaceAll(Matcher.quoteReplacement(with));
    }

    /** "swiggy@icici" says who was paid, so it stays; "9876543210@ybl", "****my-3@okhdfcbank" or a person's id don't. */
    private static String maskUpi(String s, List<String> kinds) {
        Matcher m = UPI.matcher(s);
        StringBuilder out = new StringBuilder();
        boolean any = false;
        while (m.find()) {
            String local = m.group(1);
            boolean personal = local.contains("*") || local.replaceAll("\\D", "").length() >= 4 || local.matches("(?i)[a-z]+[._-]?[a-z]+\\d*")
                    && !looksLikeBusiness(local);
            m.appendReplacement(out, Matcher.quoteReplacement(personal ? "[UPI ID]" : m.group()));
            any |= personal;
        }
        m.appendTail(out);
        if (any) {
            kinds.add("upi");
        }
        return out.toString();
    }

    /** Merchant handles are brand names; people's are name.surname or name123. Known brands and shop words stay. */
    private static boolean looksLikeBusiness(String local) {
        String l = local.toLowerCase(Locale.ROOT);
        return l.matches(".*(swiggy|zomato|amazon|flipkart|paytm|uber|ola|irctc|bigbasket|blinkit|zepto|myntra|netflix|airtel|jio|bescom|"
                + "pharm|store|mart|shop|cafe|hotel|bill|pay|merchant|recharge|fuel|petrol|electric|gas|insur|bank|card|loan|emi).*");
    }

    private static String maskAccountEnding(String s, List<String> kinds) {
        Matcher m = ACCOUNT_ENDING.matcher(s);
        if (!m.find()) {
            return s;
        }
        kinds.add("account");
        return m.replaceAll("$1[ACCOUNT]");
    }

    private static String maskLongNumbers(String s, List<String> kinds) {
        Matcher m = LONG_NUMBER.matcher(s);
        StringBuilder out = new StringBuilder();
        boolean any = false;
        while (m.find()) {
            String before = s.substring(Math.max(0, m.start() - 6), m.start());
            boolean money = CURRENCY_BEFORE.matcher(before).find();
            m.appendReplacement(out, Matcher.quoteReplacement(money ? m.group() : "[NUMBER]"));
            any |= !money;
        }
        m.appendTail(out);
        if (any) {
            kinds.add("number");
        }
        return out.toString();
    }

    /** Their full name, and their first name on its own (banks write "MANOJ M N"), become [YOU]. */
    private static String maskName(String s, String ownName, List<String> kinds) {
        if (ownName == null || ownName.isBlank()) {
            return s;
        }
        String out = s;
        String full = ownName.strip();
        String first = full.split("\\s+")[0];
        if (full.length() >= 3) {
            out = out.replaceAll("(?i)\\b" + Pattern.quote(full) + "\\b", "[YOU]");
        }
        if (first.length() >= 3) {
            // The first name with any initials or surname that follow ("MANOJ M N", "Manoj Kumar").
            out = out.replaceAll("(?i)\\b" + Pattern.quote(first) + "(?:\\s+[A-Z]\\.?){0,3}\\b(?!\\])", "[YOU]");
        }
        if (!out.equals(s)) {
            kinds.add("name");
        }
        return out;
    }
}
