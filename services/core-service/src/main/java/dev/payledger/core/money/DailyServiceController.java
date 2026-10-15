package dev.payledger.core.money;

import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/** Milk, newspaper, cook, maid (DailyServices). */
@RestController
@RequestMapping("/api/core/spaces/{spaceId}/daily-services")
class DailyServiceController {

    private final DailyServices services;

    DailyServiceController(DailyServices services) {
        this.services = services;
    }

    record Mark(int quantity) {
    }

    record Pay(YearMonth month, UUID accountId, LocalDate occurredOn) {
    }

    @GetMapping
    @Operation(summary = "Your daily services, each with a month's bill (default: this month)")
    List<DailyServices.Row> list(CurrentUser user, @PathVariable UUID spaceId, @RequestParam(required = false) YearMonth month) {
        return services.list(user, spaceId, month);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a service: a rate per DAY (a normal day's delivery) or per MONTH (a salary, with paid leaves)")
    DailyServices.Row create(CurrentUser user, @PathVariable UUID spaceId, @RequestBody DailyServices.Fields fields) {
        return services.create(user, spaceId, fields);
    }

    @PatchMapping("/{id}")
    DailyServices.Row update(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID id, @RequestBody DailyServices.Fields fields) {
        return services.update(user, spaceId, id, fields);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a service; one with paid months stops today instead, keeping them")
    void remove(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID id) {
        services.remove(user, spaceId, id);
    }

    @PutMapping("/{id}/days/{day}")
    @Operation(summary = "Mark a day: 0 missed or off, 1 normal, 2+ extra (409 month-paid)")
    DailyServices.Row mark(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID id, @PathVariable LocalDate day, @RequestBody Mark mark) {
        return services.mark(user, spaceId, id, day, mark.quantity());
    }

    @PostMapping("/{id}/payments")
    @Operation(summary = "Pay a month's bill: records one expense (409 already-paid, 422 nothing-to-pay or account-required)")
    DailyServices.Row pay(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID id, @RequestBody Pay pay) {
        return services.pay(user, spaceId, id, pay.month(), pay.accountId(), pay.occurredOn());
    }

    @DeleteMapping("/{id}/payments/{month}")
    @Operation(summary = "Undo a month's payment: its expense is deleted and the month can change again")
    DailyServices.Row unpay(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID id, @PathVariable YearMonth month) {
        return services.unpay(user, spaceId, id, month);
    }
}
