package dev.payledger.core.admin;

import dev.payledger.core.admin.StaffRole.Permission;
import dev.payledger.core.settings.AppSettings;
import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Free's limits, the trial length and the announcement banner. */
@RestController
@RequestMapping("/api/core/admin/settings")
class AdminSettingsController {

    private final StaffService staff;
    private final AppSettings settings;
    private final AuditLog audit;
    private final TransactionTemplate tx;

    AdminSettingsController(StaffService staff, AppSettings settings, AuditLog audit, TransactionTemplate tx) {
        this.staff = staff;
        this.settings = settings;
        this.audit = audit;
        this.tx = tx;
    }

    record FreeLimits(@NotNull @Min(0) @Max(1000) Integer accounts, @NotNull @Min(0) @Max(1000) Integer budgets,
                      @NotNull @Min(0) @Max(1000) Integer bills, @NotNull @Min(0) @Max(1000) Integer bigCosts,
                      @NotNull @Min(0) @Max(1000) Integer imports) {

        Map<String, Integer> asMap() {
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("accounts", accounts);
            m.put("budgets", budgets);
            m.put("bills", bills);
            m.put("big-costs", bigCosts);
            m.put("imports", imports);
            return m;
        }

        static FreeLimits of(Map<String, Integer> m) {
            return new FreeLimits(m.get("accounts"), m.get("budgets"), m.get("bills"), m.get("big-costs"), m.get("imports"));
        }
    }

    record Announcement(@NotBlank @Size(max = 280) String message, @NotNull @Pattern(regexp = "INFO|WARNING") String tone, LocalDate until) {
    }

    /** @param trialDays 0 stops offering the trial */
    record Settings(@NotNull @Valid FreeLimits freeLimits, @NotNull @Min(0) @Max(90) Integer trialDays, @Valid Announcement announcement) {
    }

    @GetMapping
    @Operation(summary = "Staff (settings manage): Free's limits, trial days and the announcement")
    Settings get(CurrentUser user) {
        staff.require(user, Permission.SETTINGS_MANAGE);
        return toSettings(settings.values());
    }

    @PutMapping
    @Operation(summary = "Staff (settings manage): change them all at once; applies to new adds, nothing existing is touched")
    Settings put(CurrentUser user, @Valid @RequestBody Settings body) {
        staff.require(user, Permission.SETTINGS_MANAGE);
        Settings before = toSettings(settings.values());
        AppSettings.Announcement announcement = body.announcement() == null ? null
                : new AppSettings.Announcement(body.announcement().message().strip(), body.announcement().tone(), body.announcement().until());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("before", before);
        details.put("after", body);
        tx.executeWithoutResult(status -> {
            settings.save(new AppSettings.Values(body.freeLimits().asMap(), body.trialDays(), announcement), user.id());
            audit.record(user.id(), "settings.update", null, details);
        });
        // A read during the transaction may have cached the old values.
        settings.refresh();
        return toSettings(settings.values());
    }

    private static Settings toSettings(AppSettings.Values v) {
        AppSettings.Announcement a = v.announcement();
        return new Settings(FreeLimits.of(v.freeLimits()), v.trialDays(),
                a == null ? null : new Announcement(a.message(), a.tone(), a.until()));
    }
}
