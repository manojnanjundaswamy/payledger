package dev.payledger.core.progress;

import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ProgressController {

    private final ProgressService progress;

    ProgressController(ProgressService progress) {
        this.progress = progress;
    }

    @GetMapping("/api/core/me/progress")
    @Operation(summary = "Your streak, badges (earned and how to earn the rest), points, level and this week's challenge; new badges are kept and announced")
    ProgressService.Progress progress(CurrentUser user) {
        return progress.progress(user);
    }
}
