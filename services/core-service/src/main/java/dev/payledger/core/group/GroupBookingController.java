package dev.payledger.core.group;

import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** Whether your share of group expenses is booked in your own budget, and from which account you usually pay. */
@RestController
@RequestMapping("/api/core/me/group-budget")
class GroupBookingController {

    private final GroupBooking booking;

    GroupBookingController(GroupBooking booking) {
        this.booking = booking;
    }

    record Update(boolean enabled, UUID accountId) {
    }

    @GetMapping
    GroupBooking.Settings get(CurrentUser user) {
        return booking.settings(user);
    }

    @PutMapping
    @Operation(summary = "Turn booking your share on or off and set the default account; turning it on books what's already in your groups")
    GroupBooking.Settings update(CurrentUser user, @RequestBody Update request) {
        return booking.update(user, request.enabled(), request.accountId());
    }

    @PostMapping("/sync")
    @Operation(summary = "Book (or unbook) every group expense and payment you're part of again")
    Map<String, Integer> sync(CurrentUser user) {
        return Map.of("entries", booking.syncAll(user.id()));
    }
}
