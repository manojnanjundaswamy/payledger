package dev.payledger.core.household;

import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Households: members, roles and invites. Their money lives under /api/core/spaces/{id}/… like any space. */
@RestController
@RequestMapping("/api/core/households")
class HouseholdController {

    private final HouseholdService households;

    HouseholdController(HouseholdService households) {
        this.households = households;
    }

    record Create(String name, String currency) {
    }

    record Rename(String name) {
    }

    record InviteIn(String email, String role) {
    }

    record RoleIn(String role) {
    }

    record Join(String token) {
    }

    @GetMapping
    List<HouseholdService.Summary> list(CurrentUser user) {
        return households.list(user);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start a household (Premium): you're its owner; invite your family next")
    HouseholdService.Household create(CurrentUser user, @RequestBody Create body) {
        return households.create(user, body.name(), body.currency());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Members, open invites, and who recorded how much of the month's spending")
    HouseholdService.Household get(CurrentUser user, @PathVariable UUID id, @RequestParam(required = false) String month) {
        return households.get(user, id, month);
    }

    @PatchMapping("/{id}")
    HouseholdService.Household rename(CurrentUser user, @PathVariable UUID id, @RequestBody Rename body) {
        return households.rename(user, id, body.name());
    }

    @PostMapping("/{id}/invites")
    @Operation(summary = "Invite someone by email: added at once if they have an account, else emailed a link")
    HouseholdService.Household invite(CurrentUser user, @PathVariable UUID id, @RequestBody InviteIn body) {
        return households.invite(user, id, body.email(), body.role());
    }

    @DeleteMapping("/{id}/invites/{inviteId}")
    HouseholdService.Household revoke(CurrentUser user, @PathVariable UUID id, @PathVariable UUID inviteId) {
        return households.revokeInvite(user, id, inviteId);
    }

    @PatchMapping("/{id}/members/{userId}")
    @Operation(summary = "Change a member's role; OWNER hands the household over")
    HouseholdService.Household role(CurrentUser user, @PathVariable UUID id, @PathVariable UUID userId, @RequestBody RoleIn body) {
        return households.setRole(user, id, userId, body.role());
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove someone, or leave (your own id)")
    void remove(CurrentUser user, @PathVariable UUID id, @PathVariable UUID userId) {
        households.remove(user, id, userId);
    }

    @GetMapping("/invite")
    @Operation(summary = "What an invite link is for, before joining")
    HouseholdService.InviteInfo inviteInfo(CurrentUser user, @RequestParam String token) {
        return households.inviteInfo(user, token);
    }

    @PostMapping("/join")
    HouseholdService.Household join(CurrentUser user, @RequestBody Join body) {
        return households.join(user, body.token());
    }
}
