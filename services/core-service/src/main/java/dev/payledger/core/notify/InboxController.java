package dev.payledger.core.notify;

import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The bell: your notifications, newest first, and marking them read. */
@RestController
class InboxController {

    private final Inbox inbox;

    InboxController(Inbox inbox) {
        this.inbox = inbox;
    }

    /** @param ids null or absent: everything */
    record Read(List<UUID> ids) {
    }

    @GetMapping("/api/core/me/inbox")
    @Operation(summary = "Your notifications, newest first, and how many are unread")
    Inbox.Page page(CurrentUser user, @RequestParam(defaultValue = "30") @Min(1) @Max(100) int limit) {
        return inbox.page(user.id(), limit);
    }

    @GetMapping("/api/core/me/inbox/unread")
    @Operation(summary = "How many notifications are unread (cheap, for the badge)")
    Map<String, Long> unread(CurrentUser user) {
        return Map.of("unread", inbox.unread(user.id()));
    }

    @PostMapping("/api/core/me/inbox/read")
    @Operation(summary = "Mark notifications read: the ids given, or all of them when ids is left out")
    Map<String, Long> read(CurrentUser user, @RequestBody Read body) {
        return Map.of("unread", inbox.markRead(user.id(), body.ids()));
    }
}
