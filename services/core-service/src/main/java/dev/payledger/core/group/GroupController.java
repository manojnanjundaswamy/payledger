package dev.payledger.core.group;

import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Groups for splitting: members, placeholders, the join link. */
@RestController
@RequestMapping("/api/core/groups")
class GroupController {

    private final GroupService groups;

    GroupController(GroupService groups) {
        this.groups = groups;
    }

    @GetMapping
    @Operation(summary = "Your groups, archived last")
    List<GroupService.Summary> list(CurrentUser user) {
        return groups.list(user);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    GroupService.Group create(CurrentUser user, @Valid @RequestBody GroupRequests.Create request) {
        return groups.create(user, request);
    }

    @GetMapping("/{groupId}")
    @Operation(summary = "A group and its members; 404 unless you are one")
    GroupService.Group get(CurrentUser user, @PathVariable UUID groupId) {
        return groups.get(user, groupId);
    }

    @PatchMapping("/{groupId}")
    GroupService.Group update(CurrentUser user, @PathVariable UUID groupId, @Valid @RequestBody GroupRequests.Update request) {
        return groups.update(user, groupId, request);
    }

    @PostMapping("/{groupId}/members")
    @Operation(summary = "Add someone by name (and email): an email with an account joins at once, otherwise a placeholder; 409 member-exists")
    GroupService.Group addMember(CurrentUser user, @PathVariable UUID groupId, @Valid @RequestBody GroupRequests.AddMember request) {
        return groups.addMember(user, groupId, request);
    }

    @DeleteMapping("/{groupId}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Leave, or (owner) remove someone; their history stays")
    void removeMember(CurrentUser user, @PathVariable UUID groupId, @PathVariable UUID memberId) {
        groups.removeMember(user, groupId, memberId);
    }

    @GetMapping("/{groupId}/link")
    @Operation(summary = "The group's join link token (made on first ask)")
    Map<String, String> link(CurrentUser user, @PathVariable UUID groupId) {
        return Map.of("token", groups.link(user, groupId));
    }

    @PostMapping("/{groupId}/link/reset")
    @Operation(summary = "A new join link; the old one stops working")
    Map<String, String> resetLink(CurrentUser user, @PathVariable UUID groupId) {
        return Map.of("token", groups.resetLink(user, groupId));
    }

    @GetMapping("/join/{token}")
    @Operation(summary = "What a join link leads to (for the join page); 404 invalid-link")
    GroupService.Invite invite(CurrentUser user, @PathVariable String token) {
        return groups.invite(user, token);
    }

    @PostMapping("/join/{token}")
    @Operation(summary = "Join by link: takes over a placeholder invited with your email, else adds you; joining twice is harmless")
    GroupService.Group join(CurrentUser user, @PathVariable String token) {
        return groups.join(user, token);
    }
}
