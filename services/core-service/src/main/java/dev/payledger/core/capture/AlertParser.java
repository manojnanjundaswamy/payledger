package dev.payledger.core.capture;

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
 * Reads an Indian bank's alert (SMS or email) into a transaction: money out or in, the amount, the date, who it was
 * with, the bank and the last digits of the account. Rules, not AI: free, instant, and the same text always reads
 * the same way. Banks word alerts differently, so this looks for the parts (an amount next to "debited", "spent",
 * "credited"…) rather than one template per bank. Anything it can't read is left for the person to look at.
 */
public final class AlertParser {

    private AlertParser() {
    }

    /** @param amount paise; @param balance paise or null; @param on null when the text has no date */
    public record Parsed(String kind, long amount, LocalDate on, String merchant, String bank, String accountHint, Long balance) {
    }

    private static final String MONEY = "(?:rs\\.?|inr|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)";
    private static final Pattern AMOUNT = Pattern.compile(MONEY, Pattern.CASE_INSENSITIVE);
    private static final Pattern DEBIT = Pattern.compile(
            "\\b(debited|spent|withdrawn|withdrawal|deducted|paid|sent|purchase|used for|transferred|debit)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREDIT = Pattern.compile(
            "\\b(credited|received|deposited|refund(?:ed)?|reversed|credit(?:ed)? of)\\b", Pattern.CASE_INSENSITIVE);
    /** Not a transaction: codes, offers, payment requests, mandates that will run later, failed attempts. */
    private static final Pattern NOT_A_TRANSACTION = Pattern.compile(
            "\\b(otp|one[- ]time password|verification code|will be debited|is due|due on|requested money|collect request|"
                    + "pre-approved|offer|cashback of up to|declined|failed|unsuccessful|e-?mandate (?:registered|created))\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BALANCE = Pattern.compile(
            "(?:avl\\.?\\s*bal(?:ance)?|available\\s+bal(?:ance)?|a/c\\s+bal(?:ance)?|\\bbal(?:ance)?)\\s*(?:is|:|-)?\\s*(?:(?:rs\\.?|inr|₹)\\s*)?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LIMIT = Pattern.compile("(?:avl\\.?\\s*lmt|available\\s+limit|credit\\s+limit|limit)\\s*(?:is|:|-)?\\s*" + MONEY, Pattern.CASE_INSENSITIVE);
    private static final Pattern ACCOUNT = Pattern.compile(
            "(?:a/c|acct|account|card|ac)\\s*(?:no\\.?|number|ending(?:\\s+(?:with|in))?)?\\s*[:\\-]?\\s*[x*•.]*\\s*([0-9]{3,6})\\b", Pattern.CASE_INSENSITIVE);
    private static final List<Pattern> MERCHANT = List.of(
            Pattern.compile("\\bInfo[:\\s-]+(?:UPI/(?:P2[AM]/)?[0-9]+/)?([^/.\\n]{2,40})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bVPA\\s+([a-z0-9._-]{2,40})@[a-z]+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(?:to|towards|at|for)\\s+(?:VPA\\s+)?([A-Za-z0-9][A-Za-z0-9 &'._@-]{1,40}?)(?=\\s+(?:on|via|ref|upi|avl|bal|thru|using|from|dated|is)\\b|[.,;(]|\\s*-\\s|$)",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(?:by|from)\\s+(?:VPA\\s+)?([A-Za-z0-9][A-Za-z0-9 &'._@-]{1,40}?)(?=\\s+(?:on|via|ref|upi|avl|bal|thru|to|dated|is)\\b|[.,;(]|\\s*-\\s|$)",
                    Pattern.CASE_INSENSITIVE),
            // HDFC masks the UPI id and names the payee in brackets: "towards VPA ****my-3@okhdfcbank (ASHA K)".
            Pattern.compile("\\bVPA\\s+\\S*@[a-z]+\\s*\\(([^)\\n]{2,40})\\)", Pattern.CASE_INSENSITIVE));
    /** Words that look like a merchant but aren't one. */
    private static final Pattern NOT_A_MERCHANT = Pattern.compile(
            "^(your|you|a/c|acct|account|card|the|rs|inr|bank|upi|imps|neft|rtgs|self|xx+\\d*|\\d+|ending.*|a\\s.*|beneficiary)$", Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> BANKS = Map.ofEntries(
            Map.entry("hdfc", "HDFC Bank"), Map.entry("icici", "ICICI Bank"), Map.entry("sbi", "SBI"), Map.entry("state bank", "SBI"),
            Map.entry("axis", "Axis Bank"), Map.entry("kotak", "Kotak"), Map.entry("yes bank", "Yes Bank"), Map.entry("idfc", "IDFC First"),
            Map.entry("indusind", "IndusInd"), Map.entry("pnb", "PNB"), Map.entry("punjab national", "PNB"), Map.entry("bank of baroda", "Bank of Baroda"),
            Map.entry("bob", "Bank of Baroda"), Map.entry("canara", "Canara Bank"), Map.entry("union bank", "Union Bank"), Map.entry("federal", "Federal Bank"),
            Map.entry("au small", "AU Bank"), Map.entry("paytm", "Paytm"), Map.entry("airtel payments", "Airtel Payments Bank"), Map.entry("amex", "Amex"),
            Map.entry("american express", "Amex"), Map.entry("hsbc", "HSBC"), Map.entry("standard chartered", "Standard Chartered"),
            Map.entry("citi", "Citi"), Map.entry("rbl", "RBL Bank"), Map.entry("bandhan", "Bandhan Bank"), Map.entry("idbi", "IDBI Bank"),
            Map.entry("indian bank", "Indian Bank"), Map.entry("onecard", "OneCard"), Map.entry("slice", "slice"));

    private static final List<DateTimeFormatter> DATES = List.of(
            pattern("dd-MMM-yy"), pattern("dd-MMM-yyyy"), pattern("dd MMM yy"), pattern("dd MMM yyyy"), pattern("ddMMMyy"), pattern("ddMMMyyyy"),
            pattern("dd-MM-yyyy"), pattern("dd-MM-yy"), pattern("dd/MM/yyyy"), pattern("dd/MM/yy"), pattern("dd.MM.yyyy"), pattern("dd.MM.yy"),
            pattern("yyyy-MM-dd"), pattern("MMM dd, yyyy"), pattern("d MMM yyyy"), pattern("d-MMM-yy"));
    private static final Pattern DATE = Pattern.compile(
            "\\b(\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[-/. ](?:\\d{1,2}|[A-Za-z]{3})[-/. ]\\d{2,4}|\\d{2}[A-Za-z]{3}\\d{2,4}|[A-Za-z]{3} \\d{1,2}, \\d{4})\\b");

    /** @return the transaction, or null when the text isn't one (or can't be read with confidence) */
    public static Parsed parse(String raw, String sender, LocalDate received) {
        String text = clean(raw);
        if (text.isEmpty() || noiseIn(text)) {
            return null;
        }
        Matcher debit = DEBIT.matcher(text);
        Matcher credit = CREDIT.matcher(text);
        int d = debit.find() ? debit.start() : Integer.MAX_VALUE;
        int c = credit.find() ? credit.start() : Integer.MAX_VALUE;
        if (d == Integer.MAX_VALUE && c == Integer.MAX_VALUE) {
            return null;
        }
        // "debited ... and credited to Swiggy": what happened to your account comes first.
        boolean out = d < c;
        int verb = Math.min(d, c);
        Long amount = amountNear(text, verb);
        if (amount == null || amount <= 0) {
            return null;
        }
        LocalDate on = date(text, received);
        return new Parsed(out ? "EXPENSE" : "INCOME", amount, on, merchant(text, out), bank(text, sender),
                account(text), balance(text));
    }

    /** A sentence warning not to share codes or details: in every bank's footer, never about the transaction. */
    private static final Pattern SAFETY_NOTE = Pattern.compile(
            "[^.!\\n]*\\b(?:never|not|don'?t|do not|beware|fraud|scam|victim)\\b[^.!\\n]*\\b(?:otp|one[- ]time password|cvv|pin|password|user ?id)\\b[^.!\\n]*[.!]?",
            Pattern.CASE_INSENSITIVE);
    /** How much of the start of an alert says what it is about (subject and first lines). */
    private static final int LEAD = 400;

    /** An OTP, an offer, a bill reminder or a failed attempt: never shown for review. */
    public static boolean noise(String raw) {
        return noiseIn(clean(raw));
    }

    /**
     * Banks end every alert with safety notes ("never share your OTP or CVV", "if a transaction failed..."): those
     * say nothing about this email, so they're left out before judging it. A debit or credit in the opening lines
     * is what the email is about; an OTP or a decline counts only when it's there too.
     */
    private static boolean noiseIn(String text) {
        String judged = SAFETY_NOTE.matcher(text).replaceAll(" ");
        if (!NOT_A_TRANSACTION.matcher(judged).find()) {
            return false;
        }
        return !strongVerb(judged.substring(0, Math.min(judged.length(), LEAD)));
    }

    /** Splits a paste of many messages: blank lines between them, or one per line when each line reads alone. */
    public static List<String> split(String paste) {
        List<String> out = new ArrayList<>();
        for (String block : paste.replace("\r", "").split("\\n\\s*\\n")) {
            String b = block.strip();
            if (b.isEmpty()) {
                continue;
            }
            String[] lines = b.split("\\n");
            long readable = java.util.Arrays.stream(lines).filter(l -> parse(l, null, LocalDate.now()) != null).count();
            if (lines.length > 1 && readable >= 2) {
                for (String l : lines) {
                    if (!l.isBlank()) {
                        out.add(l.strip());
                    }
                }
            } else {
                out.add(b);
            }
        }
        return out;
    }

    /** Plain text from an alert: tags and entities gone, one space between words. */
    public static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>|</p>|</div>|</tr>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&#8377;", "₹").replace("&#x20B9;", "₹").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&#39;", "'").replace("&quot;", "\"")
                .replaceAll("[ \\t\\x0B\\f\\u00A0]+", " ")
                .replaceAll(" *\\n[\\n ]*", "\n")
                .strip();
    }

    /** Has a past-tense money verb, so an "offer" or "due" elsewhere in the text doesn't hide a real transaction. */
    private static boolean strongVerb(String text) {
        return Pattern.compile("\\b(debited|credited|spent|withdrawn)\\b", Pattern.CASE_INSENSITIVE).matcher(text).find()
                && !Pattern.compile("\\b(otp|one[- ]time password|will be debited|declined|failed)\\b", Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    /** The amount nearest the verb that isn't a balance or a card limit. */
    private static Long amountNear(String text, int verb) {
        List<int[]> skip = new ArrayList<>();
        for (Pattern p : List.of(BALANCE, LIMIT)) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                skip.add(new int[]{m.start(), m.end()});
            }
        }
        Matcher m = AMOUNT.matcher(text);
        Long best = null;
        int bestDistance = Integer.MAX_VALUE;
        while (m.find()) {
            int at = m.start();
            if (skip.stream().anyMatch(s -> at >= s[0] && at < s[1])) {
                continue;
            }
            int distance = Math.abs(at - verb);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = paise(m.group(1));
            }
        }
        if (best == null) {
            // SBI and others: "debited by 1500.0", with no Rs.
            Matcher bare = Pattern.compile("\\b(?:debited|credited|withdrawn|sent|paid)\\s+(?:by|with|for|of)?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\b",
                    Pattern.CASE_INSENSITIVE).matcher(text);
            if (bare.find()) {
                best = paise(bare.group(1));
            }
        }
        return best;
    }

    private static Long balance(String text) {
        Matcher m = BALANCE.matcher(text);
        return m.find() ? paise(m.group(1)) : null;
    }

    private static String account(String text) {
        Matcher m = ACCOUNT.matcher(text);
        while (m.find()) {
            String digits = m.group(1);
            return digits.length() > 4 ? digits.substring(digits.length() - 4) : digits;
        }
        return null;
    }

    private static String bank(String text, String sender) {
        // The sender says it best ("alerts@hdfcbank.net", "AD-HDFCBK"); a UPI handle in the text (swiggy@icici) never does.
        if (sender != null) {
            String from = sender.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            for (var e : BANKS.entrySet().stream().sorted((a, b) -> b.getKey().length() - a.getKey().length()).toList()) {
                if (e.getKey().length() >= 3 && from.contains(e.getKey().replace(" ", ""))) {
                    return e.getValue();
                }
            }
        }
        String t = text.toLowerCase(Locale.ROOT).replaceAll("\\S+@\\S+", " ");
        // Longest names first, so "state bank" wins over a stray "sbi" inside another word.
        return BANKS.entrySet().stream().sorted((a, b) -> b.getKey().length() - a.getKey().length())
                .filter(e -> Pattern.compile("\\b" + Pattern.quote(e.getKey()) + "\\b").matcher(t).find())
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private static String merchant(String text, boolean out) {
        // "to"/"at" names whom you paid; "by"/"from" who paid you.
        for (int i : out ? new int[]{4, 0, 1, 2} : new int[]{4, 0, 1, 3, 2}) {
            Matcher m = MERCHANT.get(i).matcher(text);
            while (m.find()) {
                String name = tidy(m.group(1));
                if (name != null) {
                    return name;
                }
            }
        }
        return null;
    }

    /** "swiggy@icici" → "Swiggy", "AMAZON PAY INDIA" → "Amazon Pay India"; refs and noise dropped. */
    static String tidy(String raw) {
        String s = raw.strip().replaceAll("@[A-Za-z]+$", "").replaceAll("[._-]+", " ").replaceAll("\\s+", " ").strip();
        s = s.replaceAll("(?i)\\s+(ref\\w*|upi|txn|no|on|via)\\b.*$", "").strip();
        s = s.replaceAll("(?i)^(?:(?:neft|imps|rtgs|upi)(?:\\s+(?:cr|dr))?\\s+|(?:transfer|trf)\\s+(?:from|to)\\s+)", "").strip();
        if (s.length() < 2 || NOT_A_MERCHANT.matcher(s).matches() || s.matches(".*\\d{5,}.*")) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (String w : s.split(" ")) {
            if (w.isEmpty()) {
                continue;
            }
            out.append(out.isEmpty() ? "" : " ")
                    .append(ACRONYMS.contains(w.toUpperCase(Locale.ROOT)) ? w.toUpperCase(Locale.ROOT)
                            : Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase(Locale.ROOT));
        }
        String name = out.toString();
        return name.length() > 60 ? name.substring(0, 60) : name;
    }

    private static final java.util.Set<String> ACRONYMS = java.util.Set.of("ATM", "LIC", "SBI", "HDFC", "ICICI", "IRCTC", "BSNL", "ACT", "KFC",
            "BBQ", "DTH", "EMI", "NPS", "PPF", "ELSS", "BESCOM", "MSEB", "TNEB", "UPI", "IMPS", "NEFT");

    private static LocalDate date(String text, LocalDate received) {
        Matcher m = DATE.matcher(text);
        while (m.find()) {
            String s = m.group(1).replaceAll("\\s+", " ");
            for (DateTimeFormatter f : DATES) {
                try {
                    LocalDate d = LocalDate.parse(s, f);
                    // A date far from when it arrived is something else (a due date, a statement period).
                    if (received == null || Math.abs(d.toEpochDay() - received.toEpochDay()) <= 45) {
                        return d;
                    }
                } catch (DateTimeParseException ignored) {
                    // try the next format
                }
            }
        }
        return received;
    }

    private static long paise(String amount) {
        return new java.math.BigDecimal(amount.replace(",", "")).movePointRight(2).longValueExact();
    }

    private static DateTimeFormatter pattern(String p) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(p).toFormatter(Locale.ENGLISH);
    }
}
