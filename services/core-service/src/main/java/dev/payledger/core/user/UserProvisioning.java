package dev.payledger.core.user;

import dev.payledger.core.common.Uuid7;
import dev.payledger.core.group.GroupService;
import dev.payledger.core.money.CategoryService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Maps a Keycloak identity to a PayLedger user.
 * <p>
 * The first request of a new person creates their user row, their personal space (with the default
 * categories) and their OWNER membership in one transaction. Later requests only read (and follow an email change made
 * in Keycloak). Two first requests racing each other are safe: the insert uses
 * {@code ON CONFLICT (subject) DO NOTHING}, and only the winner creates the personal space.
 */
@Service
public class UserProvisioning {

    private final JdbcClient jdbc;
    private final CategoryService categories;
    private final GroupService groups;
    private final ActivityTracker activity;

    public UserProvisioning(JdbcClient jdbc, CategoryService categories, GroupService groups, ActivityTracker activity) {
        this.jdbc = jdbc;
        this.categories = categories;
        this.groups = groups;
        this.activity = activity;
    }

    @Transactional
    public CurrentUser resolve(Jwt jwt) {
        if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
            throw new EmailNotVerifiedException();
        }
        String subject = jwt.getSubject();
        String email = jwt.getClaimAsString("email").toLowerCase();

        Optional<CurrentUser> existing = find(subject);
        if (existing.isPresent() && disabled(existing.get().id())) {
            throw new AccountDisabledException();
        }
        CurrentUser user;
        if (existing.isEmpty()) {
            user = create(subject, email, displayName(jwt, email));
        } else if (!existing.get().email().equals(email)) {
            // Email changed in Keycloak. The display name is only taken from Keycloak once:
            // afterwards it belongs to the user's PayLedger profile.
            updateEmail(existing.get().id(), email);
            user = new CurrentUser(existing.get().id(), subject, email, existing.get().displayName(), existing.get().personalSpaceId());
        } else {
            user = existing.get();
        }
        activity.seen(user.id());
        return user;
    }

    /**
     * Creates the user and their personal space now, for an account staff made in the admin console, so it shows
     * in the console (and can be given Premium) before its first sign-in. Their first request then just finds it.
     */
    @Transactional
    public CurrentUser provision(String subject, String email, String name) {
        return find(subject).orElseGet(() -> create(subject, email.toLowerCase(), name));
    }

    private boolean disabled(java.util.UUID userId) {
        return jdbc.sql("select disabled_at is not null from users where id = :id").param("id", userId).query(Boolean.class).single();
    }

    private CurrentUser create(String subject, String email, String name) {
        UUID userId = Uuid7.next();
        // No conflict target on purpose: "on conflict (subject)" let a racing first request of the same
        // person trip over the email index instead, which surfaced as a false "email in use" (409).
        int inserted = jdbc.sql("""
                        insert into users (id, subject, email, display_name)
                        values (:id, :subject, :email, :name)
                        on conflict do nothing
                        """)
                .param("id", userId).param("subject", subject).param("email", email).param("name", name)
                .update();
        if (inserted == 0) {
            // A concurrent first request of this person won (its row is committed by now), or the
            // email belongs to someone else.
            return find(subject).orElseThrow(EmailInUseException::new);
        }
        UUID spaceId = Uuid7.next();
        jdbc.sql("""
                        insert into spaces (id, kind, name, currency, created_by)
                        values (:id, 'PERSONAL', 'Personal', 'INR', :userId)
                        """)
                .param("id", spaceId).param("userId", userId).update();
        jdbc.sql("insert into space_members (space_id, user_id, role) values (:spaceId, :userId, 'OWNER')")
                .param("spaceId", spaceId).param("userId", userId).update();
        categories.seedDefaults(spaceId);
        // Groups that invited this (verified) email before the account existed.
        groups.claimInvites(userId, email);
        return find(subject).orElseThrow();
    }

    private void updateEmail(UUID userId, String email) {
        try {
            jdbc.sql("update users set email = :email, updated_at = now() where id = :id and email <> :email")
                    .param("email", email).param("id", userId).update();
        } catch (DuplicateKeyException e) {
            throw new EmailInUseException();
        }
    }

    private Optional<CurrentUser> find(String subject) {
        return jdbc.sql("""
                        select u.id, u.subject, u.email, u.display_name, s.id as personal_space_id
                        from users u
                        join spaces s on s.created_by = u.id and s.kind = 'PERSONAL'
                        where u.subject = :subject
                        """)
                .param("subject", subject)
                .query(CurrentUser.class)
                .optional();
    }

    private static String displayName(Jwt jwt, String email) {
        String name = jwt.getClaimAsString("name");
        if (name != null && !name.isBlank()) {
            return name.strip();
        }
        String given = jwt.getClaimAsString("given_name");
        if (given != null && !given.isBlank()) {
            return given.strip();
        }
        return email.substring(0, email.indexOf('@'));
    }
}
