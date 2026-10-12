package dev.payledger.core.user;

import java.util.UUID;

/**
 * The signed-in person, resolved from the access token. Add it as a controller method parameter
 * to get it; the user and their personal space are created on their first request.
 */
public record CurrentUser(UUID id, String subject, String email, String displayName, UUID personalSpaceId) {
}
