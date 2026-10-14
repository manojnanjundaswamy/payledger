package dev.payledger.core.group;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

final class GroupExpenseRequests {

    /** ₹1,000 crore in paise, as for accounts. */
    static final long MAX_AMOUNT = 10_000_000_000_000L;

    private GroupExpenseRequests() {
    }

    /** @param value paise (payers, EXACT), basis points (PERCENT: 100% = 10000) or a weight (SHARES); ignored for EQUAL */
    record PartIn(@NotNull UUID memberId, @Min(0) @Max(MAX_AMOUNT) Long value) {
    }

    /**
     * @param category a label for the group ("Food")
     * @param paidBy   who paid, and how much each (adding up to the amount)
     * @param shares   who it is for, with the split's values
     */
    /**
     * @param paidFromAccountId optional: which of your own accounts you paid from (or, for a loan you took, received into)
     * @param loan              money lent, not shared spending: for whoever's share it is, it's money in, owed back
     * @param dueOn             optional, for a loan: when it should be paid back
     */
    record Fields(@NotBlank @Size(max = 120) String description, @NotNull @Min(1) @Max(MAX_AMOUNT) Long amount,
                  @NotNull LocalDate occurredOn, @Size(max = 40) String category,
                  @NotEmpty @Size(max = 50) List<@Valid PartIn> paidBy, @NotNull Splits.Type split,
                  @NotEmpty @Size(max = 50) List<@Valid PartIn> shares, UUID paidFromAccountId, Boolean loan, LocalDate dueOn) {
    }

    /** @param id optional, chosen by the client so a retry adds the expense once */
    record Create(UUID id, @NotBlank @Size(max = 120) String description, @NotNull @Min(1) @Max(MAX_AMOUNT) Long amount,
                  @NotNull LocalDate occurredOn, @Size(max = 40) String category,
                  @NotEmpty @Size(max = 50) List<@Valid PartIn> paidBy, @NotNull Splits.Type split,
                  @NotEmpty @Size(max = 50) List<@Valid PartIn> shares, UUID paidFromAccountId, Boolean loan, LocalDate dueOn) {

        Fields fields() {
            return new Fields(description, amount, occurredOn, category, paidBy, split, shares, paidFromAccountId, loan, dueOn);
        }
    }

    /** @param accountId optional: your account the money left (you paid) or arrived in (you were paid), for your book */
    record Settle(@NotNull UUID fromMemberId, @NotNull UUID toMemberId, @NotNull @Min(1) @Max(MAX_AMOUNT) Long amount,
                  @NotNull LocalDate occurredOn, @Size(max = 120) String note, UUID accountId) {
    }
}
