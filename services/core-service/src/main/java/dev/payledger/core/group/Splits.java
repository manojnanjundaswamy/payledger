package dev.payledger.core.group;

import dev.payledger.core.common.InvalidRequestException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who owes what of an expense, in exact paise (docs/adr/0007-groups-and-splitting.md). Every split adds up
 * to the expense: the paise left over by rounding go one each to the people first in the list, so the same
 * input always gives the same split.
 */
final class Splits {

    enum Type { EQUAL, EXACT, PERCENT, SHARES }

    /** One person in a split: a value for EXACT (paise), PERCENT (basis points, 100% = 10000) or SHARES (a weight). */
    record Part(UUID memberId, long value) {
    }

    private Splits() {
    }

    static Map<UUID, Long> split(long amount, Type type, List<Part> parts) {
        if (parts.isEmpty()) {
            throw new InvalidRequestException("invalid-split", "split it between at least one person");
        }
        if (parts.stream().map(Part::memberId).distinct().count() != parts.size()) {
            throw new InvalidRequestException("invalid-split", "someone is in the split twice");
        }
        return switch (type) {
            case EQUAL -> proportional(amount, parts.stream().map(p -> new Part(p.memberId(), 1)).toList());
            case EXACT -> exact(amount, parts);
            case PERCENT -> {
                long total = parts.stream().mapToLong(Part::value).sum();
                if (total != 10_000 || parts.stream().anyMatch(p -> p.value() < 0)) {
                    throw new InvalidRequestException("invalid-split", "the percentages must add up to 100");
                }
                yield proportional(amount, parts);
            }
            case SHARES -> {
                if (parts.stream().anyMatch(p -> p.value() < 0) || parts.stream().mapToLong(Part::value).sum() <= 0) {
                    throw new InvalidRequestException("invalid-split", "shares must be whole numbers, and not all zero");
                }
                yield proportional(amount, parts);
            }
        };
    }

    /** Payers: their amounts must add up to the expense. */
    static Map<UUID, Long> payers(long amount, List<Part> payers) {
        if (payers.isEmpty()) {
            throw new InvalidRequestException("invalid-payers", "say who paid");
        }
        if (payers.stream().map(Part::memberId).distinct().count() != payers.size() || payers.stream().anyMatch(p -> p.value() <= 0)) {
            throw new InvalidRequestException("invalid-payers", "each payer once, each with an amount above zero");
        }
        if (payers.stream().mapToLong(Part::value).sum() != amount) {
            throw new InvalidRequestException("invalid-payers", "what the payers paid must add up to the expense");
        }
        Map<UUID, Long> paid = new LinkedHashMap<>();
        payers.forEach(p -> paid.put(p.memberId(), p.value()));
        return paid;
    }

    private static Map<UUID, Long> exact(long amount, List<Part> parts) {
        if (parts.stream().anyMatch(p -> p.value() < 0) || parts.stream().mapToLong(Part::value).sum() != amount) {
            throw new InvalidRequestException("invalid-split", "the amounts must add up to the expense");
        }
        Map<UUID, Long> shares = new LinkedHashMap<>();
        parts.forEach(p -> shares.put(p.memberId(), p.value()));
        return shares;
    }

    /** amount × weight ÷ total, rounded down, then the leftover paise one each from the top of the list. */
    private static Map<UUID, Long> proportional(long amount, List<Part> parts) {
        long total = parts.stream().mapToLong(Part::value).sum();
        Map<UUID, Long> shares = new LinkedHashMap<>();
        long given = 0;
        for (Part p : parts) {
            // amount ≤ ₹1,000 crore (10^13 paise) and a weight ≤ 10,000 (requests check both): no overflow.
            long share = amount * p.value() / total;
            shares.put(p.memberId(), share);
            given += share;
        }
        long left = amount - given;
        List<UUID> order = new ArrayList<>(shares.keySet());
        for (int i = 0; left > 0; i = (i + 1) % order.size()) {
            // Only people with a non-zero weight take a leftover paisa.
            UUID id = order.get(i);
            long weight = parts.get(i).value();
            if (weight > 0) {
                shares.merge(id, 1L, Long::sum);
                left--;
            }
        }
        return shares;
    }

    /**
     * The fewest payments that settle a group: the biggest debtor pays the biggest creditor, again and again.
     * Balances are positive for those who owe and negative for those who are owed; they sum to zero.
     */
    static List<Payment> simplify(Map<UUID, Long> balances) {
        record Entry(UUID id, long amount) {
        }
        List<Entry> owe = new ArrayList<>();
        List<Entry> owed = new ArrayList<>();
        balances.forEach((id, b) -> {
            if (b > 0) {
                owe.add(new Entry(id, b));
            } else if (b < 0) {
                owed.add(new Entry(id, -b));
            }
        });
        List<Payment> payments = new ArrayList<>();
        while (!owe.isEmpty() && !owed.isEmpty()) {
            owe.sort((x, y) -> Long.compare(y.amount(), x.amount()));
            owed.sort((x, y) -> Long.compare(y.amount(), x.amount()));
            Entry debtor = owe.removeFirst();
            Entry creditor = owed.removeFirst();
            long paid = Math.min(debtor.amount(), creditor.amount());
            payments.add(new Payment(debtor.id(), creditor.id(), paid));
            if (debtor.amount() > paid) {
                owe.add(new Entry(debtor.id(), debtor.amount() - paid));
            }
            if (creditor.amount() > paid) {
                owed.add(new Entry(creditor.id(), creditor.amount() - paid));
            }
        }
        return payments;
    }

    /** {@code from} pays {@code to}. */
    record Payment(UUID from, UUID to, long amount) {
    }
}
