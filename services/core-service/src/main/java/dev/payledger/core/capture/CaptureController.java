package dev.payledger.core.capture;

import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;

/** Auto-capture: your forwarding address, pasted SMS, and the "To review" list. */
@RestController
class CaptureController {

    private final CaptureService capture;
    private final byte[] secret;

    CaptureController(CaptureService capture, @Value("${payledger.capture.inbound-secret:}") String secret) {
        this.capture = capture;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    record Paste(String text, String source) {
    }

    @GetMapping("/api/core/capture")
    @Operation(summary = "Your forwarding address, Gmail's confirmation code if one came, and the entries waiting to be reviewed")
    CaptureService.View view(CurrentUser user) {
        return capture.view(user);
    }

    @GetMapping("/api/core/capture/count")
    @Operation(summary = "How many entries wait to be reviewed")
    Map<String, Long> count(CurrentUser user) {
        return Map.of("pending", capture.pending(user));
    }

    @PostMapping("/api/core/capture/address/reset")
    @Operation(summary = "A new forwarding address; the old one stops working")
    CaptureService.View reset(CurrentUser user) {
        return capture.resetAddress(user);
    }

    @PostMapping("/api/core/capture/address/confirmed")
    @io.swagger.v3.oas.annotations.Operation(summary = "Say Gmail forwarding is confirmed (Gmail doesn't tell us); a forwarded email arriving says so too")
    CaptureService.View forwardingConfirmed(dev.payledger.core.user.CurrentUser user) {
        return capture.forwardingConfirmed(user);
    }

    @PostMapping("/api/core/capture/paste")
    @Operation(summary = "Read bank SMS or alerts you pasted or shared; each becomes an entry to review")
    CaptureService.Pasted paste(CurrentUser user, @RequestBody Paste body) {
        return capture.paste(user, body.text(), "SHARE".equals(body.source()) ? "SHARE" : "PASTE");
    }

    @PostMapping("/api/core/capture/items/{id}/add")
    @Operation(summary = "Add an entry to review as a transaction, with what you checked or changed")
    CaptureService.Item add(CurrentUser user, @PathVariable UUID id, @RequestBody CaptureService.Add body) {
        return capture.add(user, id, body);
    }

    record Ids(java.util.List<UUID> ids) {
    }

    @PostMapping("/api/core/capture/items/add")
    @Operation(summary = "Add every listed entry that's ready (account known, not entered already); the rest stay to review")
    CaptureService.AddedAll addAll(CurrentUser user, @RequestBody Ids body) {
        return capture.addAll(user, body.ids());
    }

    @PostMapping(value = "/api/core/capture/statement", consumes = "multipart/form-data")
    @Operation(summary = "Read a bank statement (CSV, Excel or PDF) into To review, from the chosen account; the PDF password is never kept")
    CaptureService.Imported statement(CurrentUser user, @org.springframework.web.bind.annotation.RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                      @org.springframework.web.bind.annotation.RequestParam("accountId") UUID accountId,
                                      @org.springframework.web.bind.annotation.RequestParam(value = "password", required = false) String password) throws java.io.IOException {
        return capture.statement(user, file.getBytes(), file.getOriginalFilename(), accountId, password);
    }

    @PostMapping("/api/core/capture/items/{id}/dismiss")
    @Operation(summary = "Not a transaction, or already entered: let it go")
    CaptureService.Item dismiss(CurrentUser user, @PathVariable UUID id) {
        return capture.dismiss(user, id);
    }

    /**
     * Email from our Cloudflare Email Worker (deploy/cloudflare/email-worker.js): the raw message, the address it was
     * sent to, and the shared secret. No user session; without a secret configured the door stays shut.
     */
    @PostMapping(value = "/inbound/email", consumes = "*/*")
    ResponseEntity<Map<String, String>> inbound(@RequestHeader(value = "X-Inbound-Secret", required = false) String given,
                                                @RequestHeader(value = "X-Envelope-To", required = false) String to,
                                                @RequestBody byte[] raw) {
        if (secret.length == 0 || given == null || !MessageDigest.isEqual(secret, given.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (raw.length > 512 * 1024) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("result", "too-large"));
        }
        return ResponseEntity.ok(Map.of("result", capture.inbound(to, raw)));
    }
}
