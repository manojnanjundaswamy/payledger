package dev.payledger.core.money;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Money that leaves on a schedule: a BILL (an expense in a category) or a SAVING (a transfer to one
 * of your own accounts). Due on {@code firstDue} and every {@code everyMonths} months after it.
 */
public record Commitment(UUID id, String kind, String name, long amount, String currency, UUID accountId,
                         UUID categoryId, UUID toAccountId, int everyMonths, LocalDate firstDue, LocalDate endsOn,
                         int version, UUID goalId, boolean auto) {

    /** Without a goal or automatic recording (older callers and tests). */
    public Commitment(UUID id, String kind, String name, long amount, String currency, UUID accountId, UUID categoryId,
                      UUID toAccountId, int everyMonths, LocalDate firstDue, LocalDate endsOn, int version) {
        this(id, kind, name, amount, currency, accountId, categoryId, toAccountId, everyMonths, firstDue, endsOn, version, null, false);
    }

    /** Due dates between the two dates (inclusive), oldest first. */
    public List<LocalDate> dueDates(LocalDate from, LocalDate to) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate last = endsOn != null && endsOn.isBefore(to) ? endsOn : to;
        // Counted from firstDue each time, never from the previous date, so a 31st doesn't drift to the 28th.
        for (int n = 0; ; n++) {
            LocalDate due = firstDue.plusMonths((long) n * everyMonths);
            if (due.isAfter(last)) {
                return dates;
            }
            if (!due.isBefore(from)) {
                dates.add(due);
            }
        }
    }

    public boolean isDueOn(LocalDate date) {
        return dueDates(date, date).contains(date);
    }
}
