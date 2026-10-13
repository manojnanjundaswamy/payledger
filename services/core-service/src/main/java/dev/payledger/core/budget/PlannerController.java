package dev.payledger.core.budget;

import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
class PlannerController {

    private final PlannerService planner;

    PlannerController(PlannerService planner) {
        this.planner = planner;
    }

    @GetMapping("/api/core/spaces/{spaceId}/planner")
    @Operation(summary = "Big yearly and one-off bills (every 6+ months, or once) with the next date and what to set aside this month")
    PlannerService.Planner planner(CurrentUser user, @PathVariable UUID spaceId) {
        return planner.planner(user, spaceId);
    }
}
