package dev.payledger.core.admin;

import dev.payledger.core.admin.StaffRole.Permission;
import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The admin console's dashboard. Every endpoint under /api/core/admin checks the caller's staff role first. */
@RestController
@RequestMapping("/api/core/admin")
class AdminController {

    private final StaffService staff;
    private final AnalyticsService analytics;

    AdminController(StaffService staff, AnalyticsService analytics) {
        this.staff = staff;
        this.analytics = analytics;
    }

    @GetMapping("/overview")
    @Operation(summary = "Staff: users, active users, plans, sign-ups and activity per day, feature adoption and weekly retention (aggregates only)")
    AnalyticsService.Overview overview(CurrentUser user, @RequestParam(defaultValue = "30") @Min(7) @Max(90) int days) {
        staff.require(user, Permission.ANALYTICS_VIEW);
        return analytics.overview(days);
    }
}
