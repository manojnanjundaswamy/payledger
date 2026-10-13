package dev.payledger.core.money;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

final class AccountRequests {

    /** ₹1,000 crore in paise: far above any personal account, small enough to never overflow a sum. */
    static final long MAX_AMOUNT = 10_000_000_000_000L;

    private AccountRequests() {
    }

    /**
     * @param currency       defaults to the space's currency
     * @param openingBalance paise, in the user's terms (owed, for a card); may be negative (overdrawn)
     * @param openingDate    defaults to today
     */
    record Create(
            @NotNull AccountKind kind,
            @NotBlank @Size(max = 60) String name,
            @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO-4217 code like INR") String currency,
            @Min(-MAX_AMOUNT) @Max(MAX_AMOUNT) Long openingBalance,
            LocalDate openingDate) {
    }

    /** Every field is optional. Kind and currency can't change: the ledger account keeps its type. */
    record Update(
            @Size(min = 1, max = 60) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
            @Min(-MAX_AMOUNT) @Max(MAX_AMOUNT) Long openingBalance,
            LocalDate openingDate,
            Boolean archived) {
    }
}
