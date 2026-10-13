package dev.payledger.core.plan;

import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/core/plan")
class PlanController {

    private final PlanService plans;

    PlanController(PlanService plans) {
        this.plans = plans;
    }

    @GetMapping
    @Operation(summary = "Your plan (FREE or PREMIUM), what gave Premium and until when, whether the trial is still available, and Free's limits with your usage")
    PlanService.Status status(CurrentUser user) {
        return plans.status(user);
    }

    @PostMapping("/trial")
    @Operation(summary = "Start the Premium trial (30 days unless changed in the admin console; once per user, no card); 409 trial-used or trial-off")
    PlanService.Status startTrial(CurrentUser user) {
        return plans.startTrial(user);
    }
}
