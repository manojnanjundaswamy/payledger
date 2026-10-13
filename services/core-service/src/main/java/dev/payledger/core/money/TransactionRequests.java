package dev.payledger.core.money;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

final class TransactionRequests {

    private TransactionRequests() {
    }

    /** What a transaction says, validated; the same for create and replace. */
    record Fields(String kind, long amount, UUID accountId, UUID toAccountId, UUID categoryId, LocalDate occurredOn,
                  String description, String note, List<String> tags) {
    }

    /**
     * @param id          optional, chosen by the client: sending the same create twice (a retry after a
     *                    timeout, a double tap) returns the first one instead of adding it twice
     * @param amount      paise, always positive
     * @param toAccountId TRANSFER only
     * @param categoryId  EXPENSE and INCOME only
     */
    record Create(
            UUID id,
            @NotNull @Pattern(regexp = "EXPENSE|INCOME|TRANSFER") String kind,
            @NotNull @Min(1) @Max(AccountRequests.MAX_AMOUNT) Long amount,
            @NotNull UUID accountId,
            UUID toAccountId,
            UUID categoryId,
            @NotNull LocalDate occurredOn,
            @NotBlank @Size(max = 120) String description,
            @Size(max = 1000) String note,
            @Size(max = 10) List<@NotBlank @Size(max = 30) String> tags) {

        Fields fields() {
            return new Fields(kind, amount, accountId, toAccountId, categoryId, occurredOn, description, note, tags);
        }
    }

    /** A full replacement. {@code version} is the version being replaced; a stale one is refused with 409. */
    record Replace(
            @NotNull Integer version,
            @NotNull @Pattern(regexp = "EXPENSE|INCOME|TRANSFER") String kind,
            @NotNull @Min(1) @Max(AccountRequests.MAX_AMOUNT) Long amount,
            @NotNull UUID accountId,
            UUID toAccountId,
            UUID categoryId,
            @NotNull LocalDate occurredOn,
            @NotBlank @Size(max = 120) String description,
            @Size(max = 1000) String note,
            @Size(max = 10) List<@NotBlank @Size(max = 30) String> tags) {

        Fields fields() {
            return new Fields(kind, amount, accountId, toAccountId, categoryId, occurredOn, description, note, tags);
        }
    }
}
