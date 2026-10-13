package dev.payledger.core.money;

import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class FxController {

    private final FxService fx;

    FxController(FxService fx) {
        this.fx = fx;
    }

    @GetMapping("/api/core/fx")
    @Operation(summary = "Reference exchange rates as 1 INR = x OTHER (ECB, plus USD-pegged Gulf currencies); 503 fx-unavailable")
    FxService.Rates rates(CurrentUser user) {
        return fx.rates();
    }
}
