package dev.payledger.ledger.movement;

import dev.payledger.ledger.journal.JournalEntryView;
import dev.payledger.ledger.journal.PostingOutcome;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Money-moving endpoints. Every one REQUIRES an {@code Idempotency-Key} header: if the client times out
 * and retries, the money moves exactly once and the retry gets the original response
 * (marked with {@code Idempotent-Replayed: true}).
 */
@RestController
@RequestMapping("/api/v1")
public class MoneyMovementController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private final MoneyMovementService service;

    public MoneyMovementController(MoneyMovementService service) {
        this.service = service;
    }

    @PostMapping("/deposits")
    ResponseEntity<JournalEntryView> deposit(@RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 128) String idempotencyKey,
                                             @Valid @RequestBody DepositRequest request) {
        return respond(service.deposit(idempotencyKey, request.accountId(), request.amount(),
                request.currency(), request.description()));
    }

    @PostMapping("/transfers")
    ResponseEntity<JournalEntryView> transfer(@RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 128) String idempotencyKey,
                                              @Valid @RequestBody TransferRequest request) {
        return respond(service.transfer(idempotencyKey, request.fromAccountId(), request.toAccountId(),
                request.amount(), request.currency(), request.description()));
    }

    private static ResponseEntity<JournalEntryView> respond(PostingOutcome outcome) {
        return ResponseEntity.status(outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(REPLAYED, String.valueOf(outcome.replayed()))
                .body(outcome.entry());
    }

    /** @param amount minor units, e.g. 1050 = $10.50 */
    record DepositRequest(
            @NotNull UUID accountId,
            @Positive long amount,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @Size(max = 255) String description) {
    }

    /** @param amount minor units, e.g. 1050 = $10.50 */
    record TransferRequest(
            @NotNull UUID fromAccountId,
            @NotNull UUID toAccountId,
            @Positive long amount,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @Size(max = 255) String description) {
    }
}
