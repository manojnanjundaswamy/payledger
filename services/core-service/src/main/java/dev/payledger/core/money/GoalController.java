package dev.payledger.core.money;

import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Savings goals and the money added to them. */
@RestController
@RequestMapping("/api/core/spaces/{spaceId}/goals")
class GoalController {

    private final GoalService goals;

    GoalController(GoalService goals) {
        this.goals = goals;
    }

    /** @param target paise; @param accountId optional: progress follows that account's balance */
    record Create(@NotBlank @Size(max = 60) String name, @Size(max = 16) String emoji,
                  @NotNull @Min(1) @Max(AccountRequests.MAX_AMOUNT) Long target, LocalDate targetDate, UUID accountId) {
        GoalService.Fields fields() {
            return new GoalService.Fields(name, emoji, target, targetDate, accountId);
        }
    }

    record Update(@NotNull Integer version, @NotBlank @Size(max = 60) String name, @Size(max = 16) String emoji,
                  @NotNull @Min(1) @Max(AccountRequests.MAX_AMOUNT) Long target, LocalDate targetDate, UUID accountId, Boolean archived) {
    }

    /** @param amount paise; negative takes money out */
    record Contribute(@NotNull @Min(-AccountRequests.MAX_AMOUNT) @Max(AccountRequests.MAX_AMOUNT) Long amount, LocalDate occurredOn, @Size(max = 120) String note) {
    }

    @GetMapping
    @Operation(summary = "Goals with progress, the monthly amount still needed and whether each is on track")
    List<GoalService.Goal> list(CurrentUser user, @PathVariable UUID spaceId, @RequestParam(defaultValue = "false") boolean includeArchived) {
        return goals.list(user, spaceId, includeArchived);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a goal; Free spaces have a limit (403 plan-limit)")
    GoalService.Goal create(CurrentUser user, @PathVariable UUID spaceId, @Valid @RequestBody Create request) {
        return goals.create(user, spaceId, request.fields());
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Change a goal (send the version you edited; 409 if it changed), or archive it")
    GoalService.Goal update(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID id, @Valid @RequestBody Update r) {
        return goals.update(user, spaceId, id, r.version(), new GoalService.Fields(r.name(), r.emoji(), r.target(), r.targetDate(), r.accountId()), r.archived());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID id) {
        goals.delete(user, spaceId, id);
    }

    @GetMapping("/{id}/contributions")
    List<GoalService.Contribution> contributions(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID id) {
        return goals.contributions(user, spaceId, id);
    }

    @PostMapping("/{id}/contributions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add money to a goal (or take it out with a negative amount); not for goals that follow an account")
    GoalService.Goal contribute(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID id, @Valid @RequestBody Contribute r) {
        return goals.contribute(user, spaceId, id, r.amount(), r.occurredOn(), r.note());
    }

    @DeleteMapping("/{id}/contributions/{contributionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteContribution(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID id, @PathVariable UUID contributionId) {
        goals.deleteContribution(user, spaceId, id, contributionId);
    }
}
