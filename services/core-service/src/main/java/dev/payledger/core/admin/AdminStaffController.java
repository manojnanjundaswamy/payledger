package dev.payledger.core.admin;

import dev.payledger.core.admin.StaffRole.Permission;
import dev.payledger.core.common.ApiException;
import dev.payledger.core.common.NotFoundException;
import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Who is staff, and with which role. Only owners (staff manage) change it. The {@code ADMIN_EMAILS} owners are listed
 * but can't be changed here, and nobody changes their own role, so the console can't lock its owners out.
 */
@RestController
@RequestMapping("/api/core/admin/staff")
class AdminStaffController {

    private final StaffService staff;
    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final TransactionTemplate tx;

    AdminStaffController(StaffService staff, JdbcClient jdbc, AuditLog audit, TransactionTemplate tx) {
        this.staff = staff;
        this.jdbc = jdbc;
        this.audit = audit;
        this.tx = tx;
    }

    /** @param configured an owner from ADMIN_EMAILS: can't be changed in the console */
    record Member(UUID userId, String email, String displayName, StaffRole role, boolean configured, Instant grantedAt, String grantedBy) {
    }

    record Grant(@NotBlank @Email String email, @NotNull StaffRole role) {
    }

    @GetMapping
    @Operation(summary = "Staff (staff manage): everyone with a role, owners first")
    List<Member> list(CurrentUser user) {
        staff.require(user, Permission.STAFF_MANAGE);
        List<Member> members = new ArrayList<>(jdbc.sql("""
                        select u.id as user_id, u.email, u.display_name, r.role, false as configured, r.granted_at, g.email as granted_by
                        from staff_roles r join users u on u.id = r.user_id left join users g on g.id = r.granted_by
                        """)
                .query(Member.class).list());
        // ADMIN_EMAILS owners who have signed in at least once.
        if (!staff.configuredOwners().isEmpty()) {
            jdbc.sql("select id as user_id, email, display_name from users where email in (:owners)")
                    .param("owners", staff.configuredOwners())
                    .query((rs, i) -> new Member(rs.getObject("user_id", UUID.class), rs.getString("email"), rs.getString("display_name"),
                            StaffRole.OWNER, true, null, null))
                    .list()
                    .forEach(owner -> {
                        members.removeIf(m -> m.userId().equals(owner.userId()));
                        members.add(owner);
                    });
        }
        members.sort(java.util.Comparator.comparing(Member::role).thenComparing(Member::email));
        return members;
    }

    @PutMapping
    @Operation(summary = "Staff (staff manage): give a user (by email, signed up already) a role, or change it")
    List<Member> grant(CurrentUser user, @Valid @RequestBody Grant body) {
        staff.require(user, Permission.STAFF_MANAGE);
        String email = body.email().strip().toLowerCase(Locale.ROOT);
        UUID target = jdbc.sql("select id from users where email = :email").param("email", email).query(UUID.class).optional()
                .orElseThrow(() -> new NotFoundException("user"));
        guard(user, target, email);
        tx.executeWithoutResult(s -> {
            String before = staff.roleOf(target).map(Enum::name).orElse("NONE");
            jdbc.sql("""
                            insert into staff_roles (user_id, role, granted_by) values (:id, :role, :by)
                            on conflict (user_id) do update set role = excluded.role, granted_by = excluded.granted_by, granted_at = now()
                            """)
                    .param("id", target).param("role", body.role().name()).param("by", user.id()).update();
            audit.record(user.id(), "staff.grant", target, Map.of("role", body.role().name(), "before", before));
        });
        return list(user);
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Staff (staff manage): remove someone's role; they become a customer again")
    void remove(CurrentUser user, @PathVariable UUID userId) {
        staff.require(user, Permission.STAFF_MANAGE);
        String email = jdbc.sql("select email from users where id = :id").param("id", userId).query(String.class).optional()
                .orElseThrow(() -> new NotFoundException("user"));
        guard(user, userId, email);
        tx.executeWithoutResult(s -> {
            String before = staff.roleOf(userId).map(Enum::name).orElse("NONE");
            if (jdbc.sql("delete from staff_roles where user_id = :id").param("id", userId).update() > 0) {
                audit.record(user.id(), "staff.remove", userId, Map.of("before", before));
            }
        });
    }

    private void guard(CurrentUser user, UUID target, String email) {
        if (target.equals(user.id())) {
            throw new Refused("self", "you can't change your own role");
        }
        if (staff.isConfiguredOwner(email)) {
            throw new Refused("protected-user", "this owner is set in ADMIN_EMAILS and can't be changed here");
        }
    }

    static class Refused extends ApiException {
        Refused(String code, String message) {
            super(HttpStatus.CONFLICT, code, message);
        }
    }
}
