package dev.payledger.core.user;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.stream.Collectors;

/** Download everything about you, or delete your account. */
@RestController
@RequestMapping("/api/core/me")
class DataRightsController {

    private final DataRightsService rights;

    DataRightsController(DataRightsService rights) {
        this.rights = rights;
    }

    record Delete(String confirm) {
    }

    @GetMapping("/export")
    @Operation(summary = "Everything PayLedger keeps about you, as one JSON file")
    ResponseEntity<String> export(CurrentUser user) {
        // Each section is already JSON from Postgres; they're joined into one object without re-parsing.
        String body = rights.export(user).entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":" + e.getValue())
                .collect(Collectors.joining(",\n", "{\"exportedAt\":\"" + java.time.Instant.now() + "\",\n", "}\n"));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payledger-my-data-" + DataRightsService.stamp() + ".json\"")
                .contentType(MediaType.APPLICATION_JSON).body(body);
    }

    @PostMapping("/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete your account and your data; confirm with your email (409 confirm-email or last-owner)")
    void delete(CurrentUser user, @RequestBody Delete request) {
        rights.delete(user, request.confirm());
    }
}
