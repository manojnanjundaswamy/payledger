package dev.payledger.core.user;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Map;

/** Partial update of the signed-in user's profile; every field is optional. */
public record ProfileUpdate(
        @Size(min = 1, max = 100) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String displayName,
        @Pattern(regexp = "[a-z]{2,3}(-[A-Z]{2})?", message = "must be a language tag like en-IN") String locale,
        @Size(max = 64) String timeZone,
        @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO-4217 code like INR") String currency,
        @Min(1) @Max(28) Integer budgetStartDay,
        @Size(max = 50, message = "at most 50 top-level keys") Map<String, Object> preferences) {

    @AssertTrue(message = "timeZone must be an IANA zone like Asia/Kolkata")
    boolean isTimeZoneValid() {
        if (timeZone == null) {
            return true;
        }
        try {
            ZoneId.of(timeZone);
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }
}
