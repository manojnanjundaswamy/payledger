package dev.payledger.core.settings;

import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;

/** The announcement staff set in the admin console, shown to everyone signed in. */
@RestController
class AnnouncementController {

    private final AppSettings settings;
    private final ZoneId zone;

    AnnouncementController(AppSettings settings, @org.springframework.beans.factory.annotation.Value("${payledger.business-zone:Asia/Kolkata}") ZoneId zone) {
        this.settings = settings;
        this.zone = zone;
    }

    @GetMapping("/api/core/announcement")
    @Operation(summary = "The current announcement (message, tone INFO or WARNING, last day), or 204 when there is none")
    ResponseEntity<AppSettings.Announcement> announcement(CurrentUser user) {
        AppSettings.Announcement a = settings.values().announcement();
        if (a == null || (a.until() != null && a.until().isBefore(LocalDate.now(zone)))) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(a);
    }
}
