package dev.payledger.core.ai;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A space's account and category names for the model, and the ids they stand for (names only ever leave the server). */
@Component
public class AiContexts {

    private final JdbcClient jdbc;

    AiContexts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @param accounts name to id (open accounts), {@code categories} "KIND:name" to id */
    public record Space(AiReader.Context context, Map<String, UUID> accounts, Map<UUID, String> accountCurrency,
                        Map<String, UUID> categories, String currency) {

        public UUID account(String name) {
            return name == null ? null : accounts.get(name);
        }

        public UUID category(String kind, String name) {
            return name == null ? null : categories.get(kind + ":" + name);
        }
    }

    private record Named(UUID id, String name, String kind, String currency) {
    }

    public Space of(UUID userId, UUID spaceId, boolean bankMessage) {
        record U(String timeZone, String currency) {
        }
        U u = jdbc.sql("select time_zone, currency from users where id = :u").param("u", userId).query(U.class).single();
        String currency = jdbc.sql("select currency from spaces where id = :s").param("s", spaceId).query(String.class).optional().orElse(u.currency());
        Map<String, UUID> accounts = new LinkedHashMap<>();
        Map<UUID, String> accountCurrency = new LinkedHashMap<>();
        for (Named a : jdbc.sql("""
                        select id, name, kind, currency from money_accounts where space_id = :s and archived_at is null and kind <> 'OWED'
                        order by created_at
                        """).param("s", spaceId).query(Named.class).list()) {
            // Names people give accounts often carry the last digits ("Axis 1180"): the model gets them masked.
            accounts.putIfAbsent(maskDigits(a.name()), a.id());
            accountCurrency.put(a.id(), a.currency());
        }
        Map<String, UUID> categories = new LinkedHashMap<>();
        for (Named c : jdbc.sql("""
                        select id, name, kind, null as currency from categories where space_id = :s and not hidden
                        order by parent_id nulls first, sort_order desc, name
                        """).param("s", spaceId).query(Named.class).list()) {
            categories.putIfAbsent(c.kind() + ":" + c.name(), c.id());
        }
        List<String> expense = names(categories, "EXPENSE:");
        List<String> income = names(categories, "INCOME:");
        // A bank message's account is matched by us from the unmasked text; the model isn't told the names at all.
        AiReader.Context ctx = new AiReader.Context(bankMessage ? List.of() : List.copyOf(accounts.keySet()), expense, income, List.of(), currency,
                LocalDate.now(ZoneId.of(u.timeZone())), bankMessage);
        return new Space(ctx, accounts, accountCurrency, categories, currency);
    }

    static String maskDigits(String name) {
        return name.replaceAll("[0-9]{3,}", "•••");
    }

    private static List<String> names(Map<String, UUID> categories, String prefix) {
        return categories.keySet().stream().filter(k -> k.startsWith(prefix)).map(k -> k.substring(prefix.length())).toList();
    }
}
