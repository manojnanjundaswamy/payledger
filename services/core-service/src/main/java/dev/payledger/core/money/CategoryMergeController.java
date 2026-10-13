package dev.payledger.core.money;

import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
class CategoryMergeController {

    private final CategoryMergeService merges;

    CategoryMergeController(CategoryMergeService merges) {
        this.merges = merges;
    }

    record Body(@NotNull UUID into) {
    }

    @PostMapping("/api/core/spaces/{spaceId}/categories/{categoryId}/merge")
    @Operation(summary = "Move every transaction (and bill) of this category to another of the same kind, then hide this one")
    CategoryMergeService.Merged merge(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID categoryId,
                                      @Valid @RequestBody Body body) {
        return merges.merge(user, spaceId, categoryId, body.into());
    }
}
