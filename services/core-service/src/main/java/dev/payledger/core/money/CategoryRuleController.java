package dev.payledger.core.money;

import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Smart categories: guesses from the description, and the rules you set. */
@RestController
@RequestMapping("/api/core/spaces/{spaceId}")
class CategoryRuleController {

    private final CategoryRuleService rules;

    CategoryRuleController(CategoryRuleService rules) {
        this.rules = rules;
    }

    record SaveRule(@NotNull @Size(min = 2, max = 60) String match, @NotNull UUID categoryId) {
    }

    @GetMapping("/categories/guess")
    @Operation(summary = "The category a description most likely belongs to: a rule, else what you chose before; 204 when there is no guess")
    org.springframework.http.ResponseEntity<CategoryRuleService.Guess> guess(CurrentUser user, @PathVariable UUID spaceId,
            @RequestParam @Pattern(regexp = "EXPENSE|INCOME") String kind, @RequestParam @Size(max = 120) String description) {
        var guess = rules.guess(user, spaceId, kind, description);
        return guess == null ? org.springframework.http.ResponseEntity.noContent().build() : org.springframework.http.ResponseEntity.ok(guess);
    }

    @GetMapping("/category-rules")
    List<CategoryRuleService.Rule> list(CurrentUser user, @PathVariable UUID spaceId) {
        return rules.list(user, spaceId);
    }

    @PostMapping("/category-rules")
    @Operation(summary = "Always use this category when a description contains these words (replaces a rule for the same words)")
    CategoryRuleService.Rule save(CurrentUser user, @PathVariable UUID spaceId, @Valid @RequestBody SaveRule request) {
        return rules.save(user, spaceId, request.match(), request.categoryId());
    }

    @PostMapping("/category-rules/{id}/apply")
    @Operation(summary = "Move past transactions with these words into the rule's category (at most 500; each is an ordinary edit)")
    CategoryRuleService.Applied apply(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID id) {
        return rules.apply(user, spaceId, id);
    }

    @DeleteMapping("/category-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID id) {
        rules.delete(user, spaceId, id);
    }
}
