package dev.payledger.ledger.api;

import dev.payledger.ledger.account.AccountType;
import dev.payledger.ledger.book.BookKind;
import dev.payledger.ledger.journal.JournalEntryView;
import dev.payledger.ledger.journal.LedgerService;
import dev.payledger.ledger.journal.PostingCommand;
import dev.payledger.ledger.journal.PostingOutcome;
import dev.payledger.ledger.journal.ReversalCommand;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Internal API for other PayLedger services (core-service), not for browsers.
 * Writes REQUIRE an {@code Idempotency-Key} header: a retry returns the original entry
 * (marked {@code Idempotent-Replayed: true}) instead of recording it twice.
 */
@RestController
@RequestMapping("/api/v2")
@Validated
class LedgerController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";
    private static final String REF = "[A-Za-z0-9:_.-]{1,128}";

    private final LedgerService ledger;
    private final LedgerQueries queries;

    LedgerController(LedgerService ledger, LedgerQueries queries) {
        this.ledger = ledger;
        this.queries = queries;
    }

    @PostMapping("/entries")
    @Operation(summary = "Record a balanced entry; the book and accounts are created on first use")
    ResponseEntity<JournalEntryView> post(@RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 128) String idempotencyKey,
                                          @Valid @RequestBody EntryRequest request) {
        return respond(ledger.post(new PostingCommand(idempotencyKey, request.bookRef(), request.bookKind(),
                request.entryType(), request.effectiveDate(), request.description(), request.sourceRef(),
                request.legs().stream()
                        .map(l -> new PostingCommand.Leg(l.accountRef(), l.accountType(), l.currency(), l.amount()))
                        .toList())));
    }

    @PostMapping("/reversals")
    @Operation(summary = "Cancel an earlier entry by posting its negation on the same business date")
    ResponseEntity<JournalEntryView> reverse(@RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 128) String idempotencyKey,
                                             @Valid @RequestBody ReversalRequest request) {
        return respond(ledger.reverse(new ReversalCommand(idempotencyKey, request.reversesIdempotencyKey(),
                request.description())));
    }

    @GetMapping("/books/{bookRef}/balances")
    List<LedgerQueries.Balance> balances(@PathVariable @Pattern(regexp = REF) String bookRef,
                                         @RequestParam(required = false) List<String> accountRef) {
        return queries.balances(bookRef, accountRef);
    }

    @GetMapping("/books/{bookRef}/totals")
    @Operation(summary = "Net movement per account between two dates (inclusive), optionally per month")
    List<LedgerQueries.PeriodTotal> totals(@PathVariable @Pattern(regexp = REF) String bookRef,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                           @RequestParam(required = false) AccountType type,
                                           @RequestParam(defaultValue = "false") boolean byMonth) {
        return queries.totals(bookRef, from, to, type, byMonth);
    }

    @GetMapping("/books/{bookRef}/accounts/{accountRef}/postings")
    StatementPage statement(@PathVariable @Pattern(regexp = REF) String bookRef,
                            @PathVariable @Pattern(regexp = REF) String accountRef,
                            @RequestParam @Pattern(regexp = "[A-Z]{3}") String currency,
                            @RequestParam(required = false) Long beforeId,
                            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        List<LedgerQueries.StatementLine> lines = queries.statement(bookRef, accountRef, currency, beforeId, limit);
        Long nextCursor = lines.size() == limit ? lines.getLast().postingId() : null;
        return new StatementPage(lines, nextCursor);
    }

    private static ResponseEntity<JournalEntryView> respond(PostingOutcome outcome) {
        return ResponseEntity.status(outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(REPLAYED, String.valueOf(outcome.replayed()))
                .body(outcome.entry());
    }

    record EntryRequest(
            @NotBlank @Pattern(regexp = REF) String bookRef,
            @NotNull BookKind bookKind,
            @NotBlank @Pattern(regexp = "[A-Z_]{1,32}") String entryType,
            @NotNull LocalDate effectiveDate,
            @Size(max = 255) String description,
            @Pattern(regexp = REF) String sourceRef,
            @NotEmpty @Size(min = 2, max = 100) List<@Valid LegRequest> legs) {
    }

    /** @param amount signed minor units: positive = debit, negative = credit */
    record LegRequest(
            @NotBlank @Pattern(regexp = REF) String accountRef,
            @NotNull AccountType accountType,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            long amount) {
    }

    record ReversalRequest(@NotBlank @Size(max = 128) String reversesIdempotencyKey, @Size(max = 255) String description) {
    }

    record StatementPage(List<LedgerQueries.StatementLine> lines, Long nextCursor) {
    }
}
