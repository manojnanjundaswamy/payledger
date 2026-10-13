package dev.payledger.core.budget;

import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.UUID;

/** Monthly budgets per spending category. A month is written {@code 2026-10} and named by the month its period starts in. */
@RestController
@RequestMapping("/api/core/spaces/{spaceId}/budgets")
class BudgetController {

    /** ₹1,000 crore in paise, the same ceiling as account amounts. */
    static final long MAX_AMOUNT = 10_000_000_000_000L;

    private final BudgetService budgets;

    BudgetController(BudgetService budgets) {
        this.budgets = budgets;
    }

    /** @param from the first budget month it applies to; defaults to the current one */
    /** @param rollover carry what isn't spent into the next month (unchanged when left out) */
    record SetLimit(@NotNull @Min(1) @Max(MAX_AMOUNT) Long amount, Boolean rollover, YearMonth from) {
    }

    @GetMapping
    @Operation(summary = "Budgets of one month (default: the current one) with spending, status and pace; spending is null if the ledger is unreachable")
    BudgetService.Month month(CurrentUser user, @PathVariable UUID spaceId, @RequestParam(required = false) YearMonth month) {
        return budgets.month(user, spaceId, month);
    }

    @PutMapping("/{categoryId}")
    @Operation(summary = "Set a category's monthly limit from a month onwards (earlier months keep theirs); returns that month")
    BudgetService.Month set(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID categoryId,
                            @Valid @RequestBody SetLimit request) {
        return budgets.set(user, spaceId, categoryId, request.amount(), request.rollover(), request.from());
    }

    @DeleteMapping("/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Stop budgeting a category from a month onwards (default: the current one); earlier months keep theirs")
    void remove(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID categoryId,
                @RequestParam(required = false) YearMonth from) {
        budgets.remove(user, spaceId, categoryId, from);
    }
}
