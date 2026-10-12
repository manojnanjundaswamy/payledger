package dev.payledger.core.user;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/core/me")
class ProfileController {

    private final ProfileService profiles;

    ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    @Operation(summary = "The signed-in user, their settings, UI preferences and spaces (created on first call)")
    Profile me(CurrentUser user) {
        return profiles.get(user);
    }

    @PatchMapping
    @Operation(summary = "Change name, locale, time zone, currency, budget start day or UI preferences")
    Profile update(CurrentUser user, @Valid @RequestBody ProfileUpdate update) {
        return profiles.update(user, update);
    }
}
