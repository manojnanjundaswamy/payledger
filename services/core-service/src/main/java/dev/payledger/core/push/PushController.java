package dev.payledger.core.push;

import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Turning push notifications on for a device, off again, and sending yourself a test. */
@RestController
@RequestMapping("/api/core/push")
class PushController {

    private final PushService push;

    PushController(PushService push) {
        this.push = push;
    }

    record Keys(String p256dh, String auth) {
    }

    record Subscribe(String endpoint, Keys keys) {
    }

    record Unsubscribe(String endpoint) {
    }

    @GetMapping("/key")
    @Operation(summary = "The server's public key (VAPID) and how many of your devices get pushes")
    Map<String, Object> key(CurrentUser user) {
        return Map.of("publicKey", push.publicKey(), "devices", push.devices(user.id()));
    }

    @PostMapping("/subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "This device's push subscription, from PushManager.subscribe (400 invalid-subscription)")
    void subscribe(CurrentUser user, @RequestBody Subscribe body, @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String ua) {
        push.subscribe(user.id(), new PushService.Subscription(body.endpoint(), body.keys() == null ? null : body.keys().p256dh(),
                body.keys() == null ? null : body.keys().auth()), ua);
    }

    @DeleteMapping("/subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unsubscribe(CurrentUser user, @RequestBody Unsubscribe body) {
        push.unsubscribe(user.id(), body.endpoint());
    }

    @PostMapping("/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Send yourself a test notification on every device that has them on")
    void test(CurrentUser user) {
        push.publish(user.id(), "Notifications are on", "This is how PayLedger will tell you about bills, budgets and money shared with you.",
                "/settings?tab=notifications", "test");
    }
}
