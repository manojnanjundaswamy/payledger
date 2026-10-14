package dev.payledger.core.money;

import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

/** Transactions as a file in the import template's columns. */
@RestController
@RequestMapping("/api/core/spaces/{spaceId}/exports")
class ExportController {

    private final ExportService exports;

    ExportController(ExportService exports) {
        this.exports = exports;
    }

    @GetMapping("/transactions.csv")
    @Operation(summary = "Transactions matching the list's filters (all optional) as CSV in the template's columns; importing it back adds nothing")
    ResponseEntity<String> csv(CurrentUser user, @PathVariable UUID spaceId, TransactionFilter filter) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name(filter.from(), filter.to()) + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(exports.csv(exports.lines(user, spaceId, filter)));
    }

    @GetMapping("/transactions.xlsx")
    @Operation(summary = "The same as an Excel workbook, with real dates and numbers")
    ResponseEntity<byte[]> xlsx(CurrentUser user, @PathVariable UUID spaceId, TransactionFilter filter) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name(filter.from(), filter.to()) + ".xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(ImportXlsx.export(exports.lines(user, spaceId, filter)));
    }

    private static String name(LocalDate from, LocalDate to) {
        return "payledger-transactions" + (from != null ? "-" + from : "") + (to != null ? "-to-" + to : "");
    }
}
