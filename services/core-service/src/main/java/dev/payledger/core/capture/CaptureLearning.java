package dev.payledger.core.capture;

import dev.payledger.core.common.Uuid7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What auto-capture learns from you. Two kinds of lesson:
 * <ul>
 *   <li>a <b>pattern</b>: an alert it couldn't read (or read the wrong way), filled in by hand, is turned into a regular
 *       expression for alerts like it: the amount you entered becomes the amount slot, the name you entered the name
 *       slot, and every other number a wildcard, so the next one from that bank reads itself;</li>
 *   <li>an <b>alias</b>: a name you corrected ("SWIGGY LTD" → "Swiggy") is used next time it's read.</li>
 * </ul>
 * Patterns are tried before the built-in rules. Any future reader (an AI fallback) records what it reads the same way.
 */
@Service
public class CaptureLearning {

    static final int MAX_PATTERNS = 200;
    private static final Pattern NUMBER = Pattern.compile("[0-9][0-9,]*(?:\\.[0-9]{1,2})?");

    private final JdbcClient jdbc;

    CaptureLearning(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Read(AlertParser.Parsed parsed, String by) {
    }

    /** Your patterns first, then the built-in rules; a name you corrected before is used either way. */
    public Read read(java.util.UUID userId, String text, String sender, LocalDate received) {
        String clean = AlertParser.clean(text);
        record P(java.util.UUID id, String pattern, String kind, String merchant) {
        }
        for (P p : jdbc.sql("select id, pattern, kind, merchant from capture_patterns where user_id = :u order by used desc, created_at desc limit :n")
                .param("u", userId).param("n", MAX_PATTERNS).query(P.class).list()) {
            Matcher m;
            try {
                m = Pattern.compile(p.pattern(), Pattern.CASE_INSENSITIVE).matcher(clean);
            } catch (PatternSyntaxException e) {
                continue;
            }
            if (!m.matches()) {
                continue;
            }
            long amount = paise(m.group("amount"));
            if (amount <= 0) {
                continue;
            }
            String merchant = group(m, "merchant") != null ? AlertParser.tidy(group(m, "merchant")) : p.merchant();
            jdbc.sql("update capture_patterns set used = used + 1 where id = :id").param("id", p.id()).update();
            AlertParser.Parsed rules = AlertParser.parse(text, sender, received);
            return new Read(new AlertParser.Parsed(p.kind(), amount, rules != null ? rules.on() : received, alias(userId, merchant),
                    rules != null ? rules.bank() : null, rules != null ? rules.accountHint() : null, rules != null ? rules.balance() : null), "LEARNED");
        }
        AlertParser.Parsed rules = AlertParser.parse(text, sender, received);
        if (rules == null) {
            return new Read(null, null);
        }
        return new Read(new AlertParser.Parsed(rules.kind(), rules.amount(), rules.on(), alias(userId, rules.merchant()), rules.bank(),
                rules.accountHint(), rules.balance()), "RULES");
    }

    /** A name as you'd have it: your correction, if you made one. */
    public String alias(java.util.UUID userId, String raw) {
        if (raw == null) {
            return null;
        }
        return jdbc.sql("select name from capture_aliases where user_id = :u and raw = :r").param("u", userId).param("r", key(raw))
                .query(String.class).optional().orElse(raw);
    }

    /**
     * Learns from what you added. {@code readAs} is what the rules made of it (null: couldn't read it).
     *
     * @return what was learned, for the toast: "pattern", "name", or null
     */
    public String learn(java.util.UUID userId, String body, AlertParser.Parsed readAs, String kind, long amount, String name) {
        String learned = null;
        if (readAs != null && readAs.merchant() != null && name != null && !name.isBlank()
                && !readAs.merchant().equalsIgnoreCase(name.strip()) && name.length() <= 120) {
            jdbc.sql("""
                            insert into capture_aliases (user_id, raw, name) values (:u, :r, :n)
                            on conflict (user_id, raw) do update set name = excluded.name
                            """).param("u", userId).param("r", key(readAs.merchant())).param("n", name.strip()).update();
            learned = "name";
        }
        boolean misread = readAs == null || !readAs.kind().equals(kind) || readAs.amount() != amount;
        if (misread && body != null) {
            String pattern = template(AlertParser.clean(body), amount, name);
            if (pattern != null) {
                long count = jdbc.sql("select count(*) from capture_patterns where user_id = :u").param("u", userId).query(Long.class).single();
                if (count < MAX_PATTERNS) {
                    String fixedName = pattern.contains("(?<merchant>") ? null : name == null || name.isBlank() ? null : name.strip();
                    jdbc.sql("""
                                    insert into capture_patterns (id, user_id, pattern, kind, merchant) values (:id, :u, :p, :k, :m)
                                    on conflict (user_id, pattern) do update set kind = excluded.kind, merchant = excluded.merchant
                                    """).param("id", Uuid7.next()).param("u", userId).param("p", pattern).param("k", kind)
                            .param("m", fixedName == null ? null : fixedName.length() > 120 ? fixedName.substring(0, 120) : fixedName).update();
                    learned = "pattern";
                }
            }
        }
        return learned;
    }

    /**
     * The alert as a pattern: the amount you entered becomes the amount slot, the name (if it appears) the name slot,
     * other numbers wildcards, spaces flexible. Null when your amount isn't in the text (nothing to anchor on).
     */
    static String template(String text, long amount, String name) {
        if (text == null || text.isBlank() || text.length() > 1000) {
            return null;
        }
        Matcher numbers = NUMBER.matcher(text);
        int amountAt = -1;
        int amountEnd = -1;
        while (numbers.find()) {
            if (paise(numbers.group()) == amount) {
                amountAt = numbers.start();
                amountEnd = numbers.end();
                break;
            }
        }
        if (amountAt < 0) {
            return null;
        }
        int nameAt = -1;
        int nameEnd = -1;
        if (name != null && name.strip().length() >= 3) {
            int i = text.toLowerCase(Locale.ROOT).indexOf(name.strip().toLowerCase(Locale.ROOT));
            if (i >= 0 && (i >= amountEnd || i + name.strip().length() <= amountAt)) {
                nameAt = i;
                nameEnd = i + name.strip().length();
            }
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (i == amountAt) {
                out.append("(?<amount>[0-9][0-9,]*(?:\\.[0-9]{1,2})?)");
                i = amountEnd;
            } else if (i == nameAt) {
                out.append("(?<merchant>.+?)");
                i = nameEnd;
            } else if (Character.isDigit(text.charAt(i))) {
                int j = i;
                while (j < text.length() && (Character.isDigit(text.charAt(j)) || text.charAt(j) == ',' || text.charAt(j) == '.')
                        && j != amountAt && j != nameAt) {
                    j++;
                }
                out.append("[0-9.,]+");
                i = j;
            } else if (Character.isWhitespace(text.charAt(i))) {
                while (i < text.length() && Character.isWhitespace(text.charAt(i)) && i != amountAt && i != nameAt) {
                    i++;
                }
                out.append("\\s+");
            } else {
                out.append(Pattern.quote(String.valueOf(text.charAt(i))));
                i++;
            }
        }
        return out.toString();
    }

    private static String group(Matcher m, String name) {
        try {
            return m.group(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String key(String raw) {
        String k = raw.strip().toLowerCase(Locale.ROOT);
        return k.length() > 120 ? k.substring(0, 120) : k;
    }

    static long paise(String amount) {
        try {
            return new BigDecimal(amount.replace(",", "")).movePointRight(2).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return -1;
        }
    }

    /** For tests and the admin console: how many lessons a person has taught it. */
    public List<Long> counts(java.util.UUID userId) {
        return List.of(
                jdbc.sql("select count(*) from capture_patterns where user_id = :u").param("u", userId).query(Long.class).single(),
                jdbc.sql("select count(*) from capture_aliases where user_id = :u").param("u", userId).query(Long.class).single());
    }
}
