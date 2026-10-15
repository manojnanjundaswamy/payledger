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
    private final dev.payledger.core.money.DataImportService imports;

    DataRightsController(DataRightsService rights, dev.payledger.core.money.DataImportService imports) {
        this.rights = rights;
        this.imports = imports;
    }

    record Delete(String confirm) {
    }

    @GetMapping("/export")
    @Operation(summary = "Everything PayLedger keeps about you, as one JSON file")
    ResponseEntity<String> export(CurrentUser user) {
        // Each section is already JSON from Postgres; they're joined into one object without re-parsing.
        String body = rights.export(user).entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":" + e.getValue())
                .collect(Collectors.joining(",\n", "{\"format\":\"" + dev.payledger.core.money.DataFormat.NAME + "\",\"version\":"
                        + dev.payledger.core.money.DataFormat.VERSION + ",\"personalSpaceId\":\"" + user.personalSpaceId()
                        + "\",\"exportedAt\":\"" + java.time.Instant.now() + "\",\n", "}\n"));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payledger-my-data-" + DataRightsService.stamp() + ".json\"")
                .contentType(MediaType.APPLICATION_JSON).body(body);
    }

    @PostMapping(value = "/import", consumes = "multipart/form-data")
    @Operation(summary = "Restore a PayLedger export into this account (it must have no accounts or entries yet)")
    dev.payledger.core.money.DataImportService.Restored restore(CurrentUser user,
            @org.springframework.web.bind.annotation.RequestParam("file") org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        if (file.getSize() > 20 * 1024 * 1024) {
            throw new dev.payledger.core.common.InvalidRequestException("import-too-large", "an export can be up to 20 MB");
        }
        return imports.restore(user, file.getBytes());
    }

    @PostMapping("/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete your account and your data; confirm with your email (409 confirm-email or last-owner)")
    void delete(CurrentUser user, @RequestBody Delete request) {
        rights.delete(user, request.confirm());
    }
}
