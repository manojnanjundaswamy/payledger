package dev.payledger.core.budget;

import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Health score and "Can I afford it?" (InsightsService). */
@RestController
@RequestMapping("/api/core/spaces/{spaceId}/insights")
class InsightsController {

    private final InsightsService insights;
    private final MonthReview review;

    InsightsController(InsightsService insights, MonthReview review) {
        this.insights = insights;
        this.review = review;
    }

    @GetMapping("/review")
    @Operation(summary = "A month looked back on (default: last month): in, out, kept, against the month before, categories that moved, budgets kept, highlights")
    MonthReview.Review review(CurrentUser user, @PathVariable UUID spaceId, @RequestParam(required = false) java.time.YearMonth month) {
        return review.review(user, spaceId, month);
    }

    @GetMapping("/health")
    @Operation(summary = "A 0-100 money health score from saving, emergency fund, card dues, budgets kept and living within income; null score with no history")
    InsightsService.Health health(CurrentUser user, @PathVariable UUID spaceId) {
        return insights.health(user, spaceId);
    }

    @GetMapping("/afford")
    @Operation(summary = "Can I afford it? amount in paise; monthly=true for an EMI or subscription. Verdict YES, MAYBE or NO with reasons")
    InsightsService.Afford afford(CurrentUser user, @PathVariable UUID spaceId, @RequestParam long amount,
                                  @RequestParam(defaultValue = "false") boolean monthly) {
        return insights.afford(user, spaceId, amount, monthly);
    }
}
