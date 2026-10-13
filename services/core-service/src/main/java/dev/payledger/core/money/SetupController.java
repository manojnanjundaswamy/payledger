package dev.payledger.core.money;

import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
class SetupController {

    private final SetupService setup;

    SetupController(SetupService setup) {
        this.setup = setup;
    }

    @PostMapping("/api/core/spaces/{spaceId}/setup")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "The first setup of a new space, all or nothing: payday, accounts, regular payments and budgets. 409 already-set-up once it has accounts")
    SetupService.Result setUp(CurrentUser user, @PathVariable UUID spaceId, @Valid @RequestBody SetupService.Request body) {
        return setup.setUp(user, spaceId, body);
    }
}
