package dev.payledger.core.user;

import dev.payledger.core.common.Uuid7;
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
 * The first request of a new person creates their user row, their personal space and their
 * OWNER membership in one transaction. Later requests only read (and follow an email change made
 * in Keycloak). Two first requests racing each other are safe: the insert uses
 * {@code ON CONFLICT (subject) DO NOTHING}, and only the winner creates the personal space.
 */
@Service
public class UserProvisioning {

    private final JdbcClient jdbc;

    public UserProvisioning(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public CurrentUser resolve(Jwt jwt) {
        if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
            throw new EmailNotVerifiedException();
        }
        String subject = jwt.getSubject();
        String email = jwt.getClaimAsString("email").toLowerCase();

        Optional<CurrentUser> existing = find(subject);
        if (existing.isEmpty()) {
            return create(subject, email, displayName(jwt, email));
        }
        CurrentUser user = existing.get();
        if (!user.email().equals(email)) {
            // Email changed in Keycloak. The display name is only taken from Keycloak once:
            // afterwards it belongs to the user's PayLedger profile.
            updateEmail(user.id(), email);
            return new CurrentUser(user.id(), subject, email, user.displayName(), user.personalSpaceId());
        }
        return user;
    }

    private CurrentUser create(String subject, String email, String name) {
        UUID userId = Uuid7.next();
        int inserted;
        try {
            inserted = jdbc.sql("""
                            insert into users (id, subject, email, display_name)
                            values (:id, :subject, :email, :name)
                            on conflict (subject) do nothing
                            """)
                    .param("id", userId).param("subject", subject).param("email", email).param("name", name)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new EmailInUseException();
        }
        if (inserted == 1) {
            UUID spaceId = Uuid7.next();
            jdbc.sql("""
                            insert into spaces (id, kind, name, currency, created_by)
                            values (:id, 'PERSONAL', 'Personal', 'INR', :userId)
                            """)
                    .param("id", spaceId).param("userId", userId).update();
            jdbc.sql("insert into space_members (space_id, user_id, role) values (:spaceId, :userId, 'OWNER')")
                    .param("spaceId", spaceId).param("userId", userId).update();
        }
        // Either we created it, or a concurrent first request did.
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
