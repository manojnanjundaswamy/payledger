package dev.payledger.ledger.account;

import dev.payledger.ledger.journal.Posting;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request) {
        Account account = service.open(request.ownerId(), request.currency());
        var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(account.getId()).toUri();
        return ResponseEntity.created(location).body(AccountResponse.of(account));
    }

    @GetMapping("/{id}")
    AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.of(service.get(id));
    }

    @GetMapping("/{id}/statement")
    StatementResponse statement(@PathVariable UUID id,
                                @RequestParam(required = false) Long beforeId,
                                @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        List<Posting> page = service.statement(id, beforeId, limit);
        Long nextCursor = page.size() == limit ? page.getLast().getId() : null;
        return new StatementResponse(page.stream().map(StatementLine::of).toList(), nextCursor);
    }

    record OpenAccountRequest(
            @NotBlank @Size(max = 64) String ownerId,
            @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO-4217 code like USD") String currency) {
    }

    record AccountResponse(UUID id, String ownerId, String currency, long balance, Instant createdAt) {
        static AccountResponse of(Account a) {
            return new AccountResponse(a.getId(), a.getOwnerId(), a.getCurrency(), a.getBalance(), a.getCreatedAt());
        }
    }

    record StatementLine(long id, UUID journalEntryId, long amount, long balanceAfter, Instant createdAt) {
        static StatementLine of(Posting p) {
            return new StatementLine(p.getId(), p.getJournalEntry().getId(), p.getAmount(), p.getBalanceAfter(), p.getCreatedAt());
        }
    }

    record StatementResponse(List<StatementLine> lines, Long nextCursor) {
    }
}
