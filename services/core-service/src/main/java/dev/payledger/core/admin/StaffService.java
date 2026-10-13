package dev.payledger.core.admin;

import dev.payledger.core.common.ApiException;
import dev.payledger.core.user.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Who is staff, and the one check every admin endpoint makes before doing anything.
 * <p>
 * Roles live in core-service's database, not in Keycloak tokens: a change takes effect on the next request
 * (a token would keep an old role for its lifetime), and the console can manage them without Keycloak admin
 * rights. The emails in {@code ADMIN_EMAILS} are OWNER regardless, so there is always a way in.
 */
@Service
public class StaffService {

    private final JdbcClient jdbc;
    private final Set<String> owners;

    public StaffService(JdbcClient jdbc, @Value("${payledger.admin.owners:}") String owners) {
        this.jdbc = jdbc;
        this.owners = Arrays.stream(owners.split(","))
                .map(e -> e.strip().toLowerCase(Locale.ROOT)).filter(e -> !e.isEmpty()).collect(Collectors.toSet());
    }

    /** The user's staff role, if any. */
    public Optional<StaffRole> roleOf(CurrentUser user) {
        if (isConfiguredOwner(user.email())) {
            return Optional.of(StaffRole.OWNER);
        }
        return roleOf(user.id());
    }

    Optional<StaffRole> roleOf(UUID userId) {
        return jdbc.sql("select role from staff_roles where user_id = :id")
                .param("id", userId).query(String.class).optional().map(StaffRole::valueOf);
    }

    Set<String> configuredOwners() {
        return owners;
    }

    boolean isConfiguredOwner(String email) {
        return owners.contains(email.toLowerCase(Locale.ROOT));
    }

    /**
     * Refuses with 403 unless the user is staff with this permission. Customers get {@code not-staff} and learn
     * nothing more; staff get {@code missing-permission}, so the console can say what their role lacks.
     */
    public StaffRole require(CurrentUser user, StaffRole.Permission permission) {
        StaffRole role = roleOf(user).orElseThrow(NotStaffException::new);
        if (!role.can(permission)) {
            throw new MissingPermissionException(role, permission);
        }
        return role;
    }

    static class NotStaffException extends ApiException {
        NotStaffException() {
            super(HttpStatus.FORBIDDEN, "not-staff", "this is for PayLedger staff only");
        }
    }

    static class MissingPermissionException extends ApiException {
        MissingPermissionException(StaffRole role, StaffRole.Permission permission) {
            super(HttpStatus.FORBIDDEN, "missing-permission",
                    "the " + role.name().toLowerCase(Locale.ROOT) + " role can't do this (needs "
                            + permission.name().toLowerCase(Locale.ROOT).replace('_', ' ') + ")");
        }
    }
}
