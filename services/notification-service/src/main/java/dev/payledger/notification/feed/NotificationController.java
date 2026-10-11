package dev.payledger.notification.feed;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "Per-user feed built from ledger events")
class NotificationController {

    private static final int MAX_LIMIT = 100;

    private final NotificationService notifications;

    NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    @Operation(summary = "Latest notifications for an owner, newest first",
            description = "Keyset pagination: pass nextCursor as beforeId to get the next page. "
                    + "Eventually consistent: a movement shows up shortly after the ledger commits it.")
    NotificationPage feed(@RequestParam String ownerId,
                          @RequestParam(required = false) Long beforeId,
                          @RequestParam(defaultValue = "20") int limit) {
        int pageSize = Math.clamp(limit, 1, MAX_LIMIT);
        List<Notification> items = notifications.feed(ownerId, beforeId, pageSize);
        Long nextCursor = items.size() == pageSize ? items.getLast().id() : null;
        return new NotificationPage(items, nextCursor);
    }

    record NotificationPage(List<Notification> items, Long nextCursor) {
    }
}
