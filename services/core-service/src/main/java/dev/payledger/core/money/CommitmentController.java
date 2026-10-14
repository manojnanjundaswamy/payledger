package dev.payledger.core.money;

import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
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

/** Commitments (bills and savings on a schedule) and their due dates. */
@RestController
@RequestMapping("/api/core/spaces/{spaceId}/commitments")
class CommitmentController {

    private final CommitmentService commitments;

    CommitmentController(CommitmentService commitments) {
        this.commitments = commitments;
    }

    @GetMapping
    List<Commitment> list(CurrentUser user, @PathVariable UUID spaceId) {
        return commitments.list(user, spaceId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a BILL (expense in a category) or SAVING (transfer to your own account) that repeats every N months")
    Commitment create(CurrentUser user, @PathVariable UUID spaceId, @Valid @RequestBody CommitmentRequests.Fields request) {
        return commitments.create(user, spaceId, request);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace it (send the version you edited; 409 if it changed meanwhile); dates already paid keep their payment")
    Commitment replace(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID id, @RequestParam int version,
                       @Valid @RequestBody CommitmentRequests.Fields request) {
        return commitments.replace(user, spaceId, id, version, request);
    }

    record Link(@jakarta.validation.constraints.NotNull LocalDate dueDate, @jakarta.validation.constraints.NotNull UUID transactionId) {
    }

    @PostMapping("/{id}/link")
    @Operation(summary = "Say that a transaction you already entered paid this due date")
    CommitmentService.Due link(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID id, @Valid @RequestBody Link request) {
        return commitments.link(user, spaceId, id, request.dueDate(), request.transactionId());
    }

    @DeleteMapping("/{id}/payments/{dueDate}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Forget which transaction paid this due date (the transaction stays)")
    void unlink(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID id,
                @PathVariable @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate dueDate) {
        commitments.unlink(user, spaceId, id, dueDate);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete the schedule; its payments stay as ordinary transactions")
    void delete(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID id) {
        commitments.delete(user, spaceId, id);
    }

    @GetMapping("/suggestions")
    @Operation(summary = "Expenses that look like monthly bills (same description, account and category in 3+ of the last 6 months, steady amount and day), not yet on the plan")
    List<CommitmentService.Suggestion> suggestions(CurrentUser user, @PathVariable UUID spaceId) {
        return commitments.suggestions(user, spaceId);
    }

    @GetMapping("/due")
    @Operation(summary = "Every due date between two dates (inclusive), paid or not, oldest first")
    List<CommitmentService.Due> due(CurrentUser user, @PathVariable UUID spaceId,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return commitments.due(user, spaceId, from, to);
    }

    @PostMapping("/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Mark a due date paid: records the expense (BILL) or transfer (SAVING); 409 already-paid")
    CommitmentService.Due pay(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID id,
                              @Valid @RequestBody CommitmentRequests.Pay request) {
        return commitments.pay(user, spaceId, id, request);
    }
}
