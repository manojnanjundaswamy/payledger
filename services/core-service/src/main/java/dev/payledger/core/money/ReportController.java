package dev.payledger.core.money;

import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Reports over the transactions a filter matches. */
@RestController
@RequestMapping("/api/core/spaces/{spaceId}/reports")
class ReportController {

    private final ReportService reports;

    ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/breakdown")
    @Operation(summary = "Spending and income by category, month, account, day, weekday, tag and place, and the biggest expenses, for the list's filters")
    ReportService.Breakdown breakdown(CurrentUser user, @PathVariable UUID spaceId, TransactionFilter filter) {
        return reports.breakdown(user, spaceId, filter);
    }
}
