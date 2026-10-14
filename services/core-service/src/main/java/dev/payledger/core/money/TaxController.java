package dev.payledger.core.money;

import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Tax deductions per section for a financial year, and which categories count towards which section. */
@RestController
@RequestMapping("/api/core/spaces/{spaceId}/tax")
class TaxController {

    private final TaxService tax;

    TaxController(TaxService tax) {
        this.tax = tax;
    }

    record Map(String section) {
    }

    @GetMapping
    @Operation(summary = "What you paid towards each deduction section (80C, 80D, HRA…) in a financial year, with the entries")
    TaxService.Report report(CurrentUser user, @PathVariable UUID spaceId, @RequestParam(required = false) Integer fy) {
        return tax.report(user, spaceId, fy);
    }

    @GetMapping(value = "/export", produces = "text/csv")
    @Operation(summary = "The year's deductions as CSV, one line per entry, for your CA")
    ResponseEntity<String> export(CurrentUser user, @PathVariable UUID spaceId, @RequestParam(required = false) Integer fy) {
        TaxService.Report r = tax.report(user, spaceId, fy);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"tax-FY" + r.fy() + "-" + String.valueOf(r.fy() + 1).substring(2) + ".csv\"")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(tax.csv(user, spaceId, fy));
    }

    @PutMapping("/mappings/{categoryId}")
    @Operation(summary = "Count a category towards a section (null: stop)")
    List<TaxService.Mapping> map(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID categoryId, @RequestBody Map body) {
        return tax.map(user, spaceId, categoryId, body.section());
    }
}
