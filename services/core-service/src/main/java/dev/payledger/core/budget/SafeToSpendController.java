package dev.payledger.core.budget;

import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
class SafeToSpendController {

    private final SafeToSpendService safeToSpend;

    SafeToSpendController(SafeToSpendService safeToSpend) {
        this.safeToSpend = safeToSpend;
    }

    @GetMapping("/api/core/spaces/{spaceId}/safe-to-spend")
    @Operation(summary = "This budget month: income - spent - bills still due - savings due, and per day for the days left; null amounts if the ledger is down")
    SafeToSpendService.SafeToSpend current(CurrentUser user, @PathVariable UUID spaceId) {
        return safeToSpend.current(user, spaceId);
    }
}
