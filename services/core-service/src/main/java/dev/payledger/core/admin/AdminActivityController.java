package dev.payledger.core.admin;

import dev.payledger.core.admin.StaffRole.Permission;
import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What users did: sign-ins and changes (route, ids, outcome), never amounts or descriptions. */
@RestController
class AdminActivityController {

    private final StaffService staff;
    private final JdbcClient jdbc;

    AdminActivityController(StaffService staff, JdbcClient jdbc) {
        this.staff = staff;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/core/admin/activity")
    @Operation(summary = "Staff (users view): recent sign-ins and changes by everyone, newest first; type SIGN_IN or CHANGE, failed=true for errors only")
    List<Map<String, Object>> all(CurrentUser user, @RequestParam(required = false) @Pattern(regexp = "SIGN_IN|CHANGE") String type,
                                  @RequestParam(defaultValue = "false") boolean failed,
                                  @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        staff.require(user, Permission.USERS_VIEW);
        return jdbc.sql("""
                        select e.id, e.at, e.type, e.action, e.status, e.target::text as target, e.ip, e.user_agent, e.user_id, u.email
                        from user_events e join users u on u.id = e.user_id
                        where (cast(:type as text) is null or e.type = :type) and (not :failed or e.status >= 400)
                        order by e.at desc limit :limit
                        """)
                .param("type", type).param("failed", failed).param("limit", limit).query().listOfRows();
    }

    @GetMapping("/api/core/admin/users/{id}/activity")
    @Operation(summary = "Staff (users view): one user's recent sign-ins and changes")
    List<Map<String, Object>> one(CurrentUser user, @PathVariable UUID id, @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        staff.require(user, Permission.USERS_VIEW);
        return jdbc.sql("""
                        select id, at, type, action, status, target::text as target, ip, user_agent from user_events
                        where user_id = :id order by at desc limit :limit
                        """)
                .param("id", id).param("limit", limit).query().listOfRows();
    }
}
