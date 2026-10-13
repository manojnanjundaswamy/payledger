package dev.payledger.core.group;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

final class GroupRequests {

    private GroupRequests() {
    }

    /** @param currency defaults to INR */
    record Create(@NotBlank @Size(max = 60) String name, @Size(max = 16) String emoji,
                  @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO-4217 code like INR") String currency) {
    }

    /** Every field is optional; archiving is for the owner. */
    record Update(@Size(min = 1, max = 60) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
                  @Size(max = 16) String emoji, Boolean archived) {
    }

    record AddMember(@NotBlank @Size(max = 60) String name, @Email @Size(max = 254) String email) {
    }
}
