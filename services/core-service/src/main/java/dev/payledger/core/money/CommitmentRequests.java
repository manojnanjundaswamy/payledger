package dev.payledger.core.money;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

final class CommitmentRequests {

    private CommitmentRequests() {
    }

    /**
     * @param kind        BILL (an expense: needs categoryId) or SAVING (a transfer: needs toAccountId)
     * @param amount      paise, per due date
     * @param everyMonths 1 monthly, 3 quarterly, 6 half-yearly, 12 yearly (2 and 4 too)
     * @param firstDue    the first due date; later ones fall on the same day of the month
     * @param endsOn      optional: the last date it can be due, e.g. an EMI's last instalment
     */
    record Fields(
            @NotNull @Pattern(regexp = "BILL|SAVING") String kind,
            @NotBlank @Size(max = 60) String name,
            @NotNull @Min(1) @Max(AccountRequests.MAX_AMOUNT) Long amount,
            @NotNull UUID accountId,
            UUID categoryId,
            UUID toAccountId,
            @NotNull Integer everyMonths,
            @NotNull LocalDate firstDue,
            LocalDate endsOn,
            // Optional: a saving made for this goal; each payment counts towards it.
            UUID goalId) {

        @AssertTrue(message = "everyMonths must be 1, 2, 3, 4, 6 or 12")
        boolean isEveryMonthsValid() {
            return everyMonths == null || List.of(1, 2, 3, 4, 6, 12).contains(everyMonths);
        }
    }

    /**
     * @param amount     what was actually paid, if not the usual amount (a bigger electricity bill)
     * @param occurredOn when it was paid; defaults to the due date
     */
    record Pay(@NotNull LocalDate dueDate, @Min(1) @Max(AccountRequests.MAX_AMOUNT) Long amount, LocalDate occurredOn) {
    }
}
