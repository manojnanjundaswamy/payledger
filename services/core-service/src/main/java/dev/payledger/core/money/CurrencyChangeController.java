package dev.payledger.core.money;

import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/** Changing a space's currency: see what would happen, then do it (CurrencyChange). */
@RestController
@RequestMapping("/api/core/spaces/{spaceId}/currency")
class CurrencyChangeController {

    private final CurrencyChange change;

    CurrencyChangeController(CurrencyChange change) {
        this.change = change;
    }

    record Request(String to, CurrencyChange.Mode mode, BigDecimal rate) {
    }

    @GetMapping("/preview")
    @Operation(summary = "What changing to {to} would do: the rate, how much moves, and an example amount before and after")
    CurrencyChange.Plan preview(CurrentUser user, @PathVariable UUID spaceId, @RequestParam String to,
                                @RequestParam(defaultValue = "CONVERT") CurrencyChange.Mode mode, @RequestParam(required = false) BigDecimal rate) {
        return change.preview(user, spaceId, to, mode, rate);
    }

    @PostMapping
    @Operation(summary = "Change the space's currency: CONVERT at a rate (today's, or yours) or RELABEL (same numbers); all or nothing")
    CurrencyChange.Plan apply(CurrentUser user, @PathVariable UUID spaceId, @RequestBody Request request) {
        return change.apply(user, spaceId, request.to(), request.mode() == null ? CurrencyChange.Mode.CONVERT : request.mode(), request.rate());
    }
}
