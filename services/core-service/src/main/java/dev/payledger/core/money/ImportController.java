package dev.payledger.core.money;

import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Imports from the CSV template: preview, import, list, undo, and the template itself. */
@RestController
@RequestMapping("/api/core/spaces/{spaceId}/imports")
class ImportController {

    private final ImportService imports;

    ImportController(ImportService imports) {
        this.imports = imports;
    }

    /**
     * @param csv the file's text (UTF-8); at most {@value ImportService#MAX_ROWS} rows
     */
    record Body(@Size(max = 200) String fileName, @Size(max = 2_000_000) String csv,
                @Size(max = 500) Map<String, String> accounts, @Size(max = 500) Map<String, String> categories) {

        ImportService.Request request() {
            return new ImportService.Request(fileName, csv, accounts, categories);
        }
    }

    @PostMapping("/preview")
    @Operation(summary = "Check every row without saving anything: READY, DUPLICATE or ERROR (with column and reason), and the unknown accounts and categories to map")
    ImportService.Preview preview(CurrentUser user, @PathVariable UUID spaceId, @Valid @RequestBody Body body) {
        return imports.preview(user, spaceId, body.request());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Import the READY rows (mapped accounts and categories marked new are created first); duplicates and errors are skipped")
    ImportService.Result commit(CurrentUser user, @PathVariable UUID spaceId, @Valid @RequestBody Body body) {
        return imports.commit(user, spaceId, body.request());
    }

    @GetMapping
    List<ImportService.Import> list(CurrentUser user, @PathVariable UUID spaceId) {
        return imports.list(user, spaceId);
    }

    @DeleteMapping("/{importId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Undo an import: deletes every transaction it added (the ledger reverses them)")
    void undo(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID importId) {
        imports.undo(user, spaceId, importId);
    }

    @GetMapping(value = "/template.csv", produces = "text/csv")
    @Operation(summary = "The template, with your own account names in its example rows")
    ResponseEntity<String> template(CurrentUser user, @PathVariable UUID spaceId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payledger-template.csv\"")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(imports.template(user, spaceId));
    }
}
