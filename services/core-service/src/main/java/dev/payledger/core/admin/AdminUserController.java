package dev.payledger.core.admin;

import dev.payledger.core.admin.StaffRole.Permission;
import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Users and their plans, for staff. Each endpoint names the permission it needs. */
@RestController
@RequestMapping("/api/core/admin")
class AdminUserController {

    private final StaffService staff;
    private final AdminUserService users;
    private final AuditLog audit;

    AdminUserController(StaffService staff, AdminUserService users, AuditLog audit) {
        this.staff = staff;
        this.users = users;
        this.audit = audit;
    }

    record CreateUser(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 100) String displayName,
                      LocalDate premiumUntil) {
    }

    record Disable(@NotNull Boolean disabled) {
    }

    record SendEmail(@NotNull AdminUserService.Email kind) {
    }

    record Premium(@NotNull LocalDate until) {
    }

    @GetMapping("/users")
    @Operation(summary = "Staff (users view): find users by email or name, filter by plan (FREE, TRIAL, PAID, GRANTED, COMPLIMENTARY or PREMIUM) or status (ACTIVE, DISABLED); newest first")
    AdminUserService.Page list(CurrentUser user, @RequestParam(required = false) @Size(max = 100) String q,
                               @RequestParam(required = false) @Pattern(regexp = "FREE|TRIAL|PAID|GRANTED|COMPLIMENTARY|PREMIUM") String plan,
                               @RequestParam(required = false) @Pattern(regexp = "ACTIVE|DISABLED") String status,
                               @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
                               @RequestParam(defaultValue = "0") @Min(0) int offset) {
        staff.require(user, Permission.USERS_VIEW);
        return users.list(q, plan, status, limit, offset);
    }

    @GetMapping("/users/{id}")
    @Operation(summary = "Staff (users view): one user's profile, plan, usage counts and admin history; never their money")
    AdminUserService.Detail get(CurrentUser user, @PathVariable UUID id) {
        staff.require(user, Permission.USERS_VIEW);
        return users.get(id);
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Staff (users manage): create a user; they get an email to verify their address and choose a password. 409 email-in-use")
    AdminUserService.Detail create(CurrentUser user, @Valid @RequestBody CreateUser body) {
        staff.require(user, Permission.USERS_MANAGE);
        return users.create(user, body.email(), body.displayName(), body.premiumUntil());
    }

    @PutMapping("/users/{id}/disabled")
    @Operation(summary = "Staff (users manage): disable or enable a user (sign-in and API). Staff users need staff manage; never yourself or an ADMIN_EMAILS owner")
    AdminUserService.Detail disable(CurrentUser user, @PathVariable UUID id, @Valid @RequestBody Disable body) {
        staff.require(user, Permission.USERS_MANAGE);
        return users.setDisabled(user, id, body.disabled());
    }

    @PostMapping("/users/{id}/emails")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Staff (users support): email the user a password reset (PASSWORD_RESET) or verification (VERIFY_EMAIL) link")
    void sendEmail(CurrentUser user, @PathVariable UUID id, @Valid @RequestBody SendEmail body) {
        staff.require(user, Permission.USERS_SUPPORT);
        users.sendEmail(user, id, body.kind());
    }

    @PostMapping("/users/{id}/sign-out")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Staff (users support): end all of the user's sessions")
    void signOut(CurrentUser user, @PathVariable UUID id) {
        staff.require(user, Permission.USERS_SUPPORT);
        users.signOut(user, id);
    }

    @PutMapping("/users/{id}/premium")
    @Operation(summary = "Staff (plans manage): give Premium until the end of a date (today to 5 years); replaces a trial or earlier grant. 409 paid-subscription")
    AdminUserService.Detail grant(CurrentUser user, @PathVariable UUID id, @Valid @RequestBody Premium body) {
        staff.require(user, Permission.PLANS_MANAGE);
        return users.grantPremium(user, id, body.until());
    }

    @DeleteMapping("/users/{id}/premium")
    @Operation(summary = "Staff (plans manage): end a trial or given Premium now. 409 not-ending")
    AdminUserService.Detail end(CurrentUser user, @PathVariable UUID id) {
        staff.require(user, Permission.PLANS_MANAGE);
        return users.endPremium(user, id);
    }

    @GetMapping("/audit")
    @Operation(summary = "Staff (audit view): every admin action, newest first")
    List<AuditLog.Entry> audit(CurrentUser user, @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit,
                               @RequestParam(defaultValue = "0") @Min(0) int offset) {
        staff.require(user, Permission.AUDIT_VIEW);
        return audit.list(null, limit, offset);
    }
}
