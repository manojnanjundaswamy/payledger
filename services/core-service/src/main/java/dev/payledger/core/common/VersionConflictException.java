package dev.payledger.core.common;

import org.springframework.http.HttpStatus;

/**
 * Optimistic locking: the client edited an old version (someone else, or another tab, changed it
 * first). The client reloads and tries again instead of silently overwriting the other change.
 */
public class VersionConflictException extends ApiException {

    public VersionConflictException(String what) {
        super(HttpStatus.CONFLICT, "version-conflict", "this " + what + " was changed elsewhere; reload and try again");
    }
}
