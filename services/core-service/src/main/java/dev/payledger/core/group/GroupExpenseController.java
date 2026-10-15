package dev.payledger.core.group;

import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
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

import java.util.UUID;

/** A group's shared expenses, settlements and balances. */
@RestController
@RequestMapping("/api/core/groups/{groupId}")
class GroupExpenseController {

    private final GroupExpenseService expenses;
    private final GroupService groups;
    private final GroupComments comments;

    GroupExpenseController(GroupExpenseService expenses, GroupService groups, GroupComments comments) {
        this.expenses = expenses;
        this.groups = groups;
        this.comments = comments;
    }

    record NewComment(String body) {
    }

    @GetMapping("/comments")
    @io.swagger.v3.oas.annotations.Operation(summary = "Comments on the group's expenses, oldest first")
    java.util.List<GroupComments.Comment> comments(CurrentUser user, @PathVariable UUID groupId) {
        return comments.list(groups.doer(user, groupId), groupId);
    }

    @PostMapping("/expenses/{expenseId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    GroupComments.Comment comment(CurrentUser user, @PathVariable UUID groupId, @PathVariable UUID expenseId, @RequestBody NewComment body) {
        return comments.add(groups.doer(user, groupId), groupId, expenseId, body.body());
    }

    @DeleteMapping("/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteComment(CurrentUser user, @PathVariable UUID groupId, @PathVariable UUID commentId) {
        comments.delete(groups.doer(user, groupId), groupId, commentId);
    }

    @GetMapping("/activity")
    @Operation(summary = "The group's expenses (with each member's paid and share) and settlements, newest first")
    GroupExpenseService.Activity activity(CurrentUser user, @PathVariable UUID groupId) {
        return expenses.activity(user, groupId);
    }

    @GetMapping("/history")
    @Operation(summary = "Everything that happened in the group, newest first: who did it, when, and the details")
    java.util.List<GroupHistory.Event> history(CurrentUser user, @PathVariable UUID groupId) {
        return expenses.history(user, groupId);
    }

    @GetMapping("/insights")
    @Operation(summary = "Totals for charts: spent, each member's paid and share, by label, by month, and the biggest expenses")
    GroupExpenseService.Insights insights(CurrentUser user, @PathVariable UUID groupId) {
        return expenses.insights(user, groupId);
    }

    @PostMapping("/expenses")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add an expense: who paid (adding up), split EQUAL, EXACT (paise), PERCENT (basis points) or SHARES")
    GroupExpenseService.Expense create(CurrentUser user, @PathVariable UUID groupId, @Valid @RequestBody GroupExpenseRequests.Create request) {
        return expenses.create(user, groupId, request);
    }

    @PutMapping("/expenses/{expenseId}")
    @Operation(summary = "Replace an expense (send the version you edited; 409 if it changed); the ledger reverses the old one")
    GroupExpenseService.Expense replace(CurrentUser user, @PathVariable UUID groupId, @PathVariable UUID expenseId,
                                        @RequestParam int version, @Valid @RequestBody GroupExpenseRequests.Fields request) {
        return expenses.replace(user, groupId, expenseId, version, request);
    }

    @DeleteMapping("/expenses/{expenseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(CurrentUser user, @PathVariable UUID groupId, @PathVariable UUID expenseId, @RequestParam int version) {
        expenses.delete(user, groupId, expenseId, version);
    }

    @PostMapping("/settlements")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record that one member paid another (cash, UPI, bank)")
    GroupExpenseService.Settlement settle(CurrentUser user, @PathVariable UUID groupId, @Valid @RequestBody GroupExpenseRequests.Settle request) {
        return expenses.settle(user, groupId, request);
    }

    @DeleteMapping("/settlements/{settlementId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteSettlement(CurrentUser user, @PathVariable UUID groupId, @PathVariable UUID settlementId) {
        expenses.deleteSettlement(user, groupId, settlementId);
    }

    @GetMapping("/balances")
    @Operation(summary = "Each member's balance from the ledger (positive owes, negative is owed) and the fewest payments that settle everyone")
    GroupExpenseService.Balances balances(CurrentUser user, @PathVariable UUID groupId) {
        return expenses.balances(user, groupId);
    }
}
