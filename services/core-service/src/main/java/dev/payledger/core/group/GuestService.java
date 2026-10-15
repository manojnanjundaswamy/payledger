package dev.payledger.core.group;

import dev.payledger.core.common.ApiException;
import dev.payledger.core.common.InvalidRequestException;
import dev.payledger.core.common.Uuid7;
import dev.payledger.core.notify.Email;
import dev.payledger.core.notify.Notifications;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Guests: people in a group who have no account (product plan 3.1–3.2). From the group's join link they pick their
 * name (a placeholder) or give one, prove their email with a 6-digit code, and get a guest session: a random token in
 * an httpOnly cookie, kept here only as a hash. The session reaches the groups where that email is a member without an
 * account, and nothing else. Signing up later with the same email turns those memberships into the account's.
 */
@Service
public class GuestService {

    static final Duration SESSION = Duration.ofDays(30);
    private static final Duration CODE = Duration.ofMinutes(10);
    private static final int CODES_PER_HOUR = 5;
    private static final int ATTEMPTS = 5;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final GroupService groups;
    private final GroupHistory history;
    private final Notifications notifications;

    GuestService(JdbcClient jdbc, GroupService groups, GroupHistory history, Notifications notifications) {
        this.jdbc = jdbc;
        this.groups = groups;
        this.history = history;
        this.notifications = notifications;
    }

    /** A name still waiting for its person: guests pick theirs. */
    public record Spot(UUID id, String name) {
    }

    /** What the join page shows someone without an account. */
    public record Link(String groupName, String emoji, int members, List<Spot> spots) {
    }

    public record CodeSent(UUID codeId, String email) {
    }

    public record GuestGroup(UUID id, String name, String emoji, String currency, UUID you) {
    }

    public record Me(String email, String name, List<GuestGroup> groups) {
    }

    @Transactional(readOnly = true)
    public Link link(String token) {
        UUID groupId = groups.groupOf(token);
        record Row(String name, String emoji) {
        }
        Row g = jdbc.sql("select name, emoji from groups where id = :id").param("id", groupId).query(Row.class).single();
        int members = jdbc.sql("select count(*)::int from group_members where group_id = :id and left_at is null").param("id", groupId)
                .query(Integer.class).single();
        List<Spot> spots = jdbc.sql("""
                        select id, display_name as name from group_members
                        where group_id = :id and user_id is null and left_at is null order by created_at
                        """).param("id", groupId).query(Spot.class).list();
        return new Link(g.name(), g.emoji(), members, spots);
    }

    /**
     * Sends a code to {@code email} for joining as the placeholder {@code memberId}, or as a new member called
     * {@code name}. Someone with an account signs in instead, so their share reaches their budget.
     */
    @Transactional
    public CodeSent sendCode(String token, UUID memberId, String name, String rawEmail) {
        UUID groupId = groups.groupOf(token);
        String email = rawEmail == null ? "" : rawEmail.strip().toLowerCase();
        if (email.length() > 320 || !EMAIL.matcher(email).matches()) {
            throw new InvalidRequestException("invalid-email", "enter a valid email address");
        }
        if (jdbc.sql("select exists (select 1 from users where lower(email) = :e)").param("e", email).query(Boolean.class).single()) {
            throw new ApiException(HttpStatus.CONFLICT, "has-account", "you already have a PayLedger account: sign in to join") {
            };
        }
        String cleanName = name == null || name.isBlank() ? null : name.strip();
        if (memberId != null) {
            record Spot(UUID id, String email) {
            }
            Spot spot = jdbc.sql("""
                            select id, email from group_members where id = :m and group_id = :g and user_id is null and left_at is null
                            """).param("m", memberId).param("g", groupId).query(Spot.class).optional()
                    .orElseThrow(() -> new InvalidRequestException("spot-taken", "that name is no longer free; pick another or enter yours"));
            if (spot.email() != null && !spot.email().equalsIgnoreCase(email)) {
                throw new InvalidRequestException("email-mismatch", "that name was invited with another email; use that one, or enter your own name");
            }
        } else if (cleanName == null || cleanName.length() > 60) {
            throw new InvalidRequestException("invalid-name", "enter your name (up to 60 letters)");
        }
        int recent = jdbc.sql("select count(*)::int from guest_codes where lower(email) = :e and created_at > now() - interval '1 hour'")
                .param("e", email).query(Integer.class).single();
        if (recent >= CODES_PER_HOUR) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too-many-codes", "too many codes for this email; try again in an hour") {
            };
        }
        UUID id = Uuid7.next();
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        jdbc.sql("""
                        insert into guest_codes (id, email, group_id, member_id, name, code_hash, expires_at)
                        values (:id, :e, :g, :m, :n, :h, now() + cast(:ttl as interval))
                        """).param("id", id).param("e", email).param("g", groupId).param("m", memberId).param("n", cleanName)
                .param("h", hash(id + ":" + code)).param("ttl", CODE.toMinutes() + " minutes").update();
        String group = jdbc.sql("select name from groups where id = :id").param("id", groupId).query(String.class).single();
        notifications.toAddress(email, "GUEST_CODE", "guest-code:" + id,
                Email.titled(code + " is your code for " + group)
                        .heading("Your code: " + code)
                        .para("Enter it on the page you just opened to join " + group + " on PayLedger. It works for 10 minutes.")
                        .footer("You got this because someone entered this email on a PayLedger join link. If it wasn't you, ignore it.", null));
        return new CodeSent(id, email);
    }

    public record Joined(String token, UUID groupId) {
    }

    /** Checks the code; on success the guest is in the group and gets a session token (for the cookie). */
    @Transactional(noRollbackFor = ApiException.class)
    public Joined verify(UUID codeId, String code) {
        CodeRow r = jdbc.sql("""
                        select email, group_id, member_id, name, code_hash, attempts, used_at is null and expires_at > now() as live
                        from guest_codes where id = :id for update
                        """).param("id", codeId).query(CodeRow.class).optional().orElse(null);
        if (r == null || !r.live() || r.attempts() >= ATTEMPTS) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "code-expired", "that code has expired; ask for a new one") {
            };
        }
        if (code == null || !MessageDigest.isEqual(hash(codeId + ":" + code.strip()).getBytes(StandardCharsets.UTF_8),
                r.codeHash().getBytes(StandardCharsets.UTF_8))) {
            // Kept even though the request fails (noRollbackFor), so guessing runs out after five tries.
            jdbc.sql("update guest_codes set attempts = attempts + 1 where id = :id").param("id", codeId).update();
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "wrong-code", "that code isn't right; check the email and try again") {
            };
        }
        jdbc.sql("update guest_codes set used_at = now() where id = :id").param("id", codeId).update();
        groups.lockGroup(r.groupId());
        join(r);
        String token = newToken();
        jdbc.sql("insert into guest_sessions (token_hash, email, expires_at) values (:h, :e, now() + cast(:ttl as interval))")
                .param("h", hash(token)).param("e", r.email()).param("ttl", SESSION.toDays() + " days").update();
        return new Joined(token, r.groupId());
    }

    private record CodeRow(String email, UUID groupId, UUID memberId, String name, String codeHash, int attempts, boolean live) {
    }

    /** The guest's membership: the spot they picked, a spot invited with their email, or a new one. */
    private UUID join(CodeRow r) {
        UUID existing = jdbc.sql("""
                        select id from group_members where group_id = :g and lower(email) = :e and user_id is null and left_at is null
                        """).param("g", r.groupId()).param("e", r.email()).query(UUID.class).optional().orElse(null);
        if (existing != null) {
            return existing;
        }
        if (r.memberId() != null) {
            int claimed = jdbc.sql("""
                            update group_members set email = :e where id = :m and user_id is null and left_at is null
                              and (email is null or lower(email) = :e)
                            """).param("e", r.email()).param("m", r.memberId()).update();
            if (claimed == 0) {
                throw new InvalidRequestException("spot-taken", "that name is no longer free; pick another or enter yours");
            }
            String name = jdbc.sql("select display_name from group_members where id = :m").param("m", r.memberId()).query(String.class).single();
            history.record(r.groupId(), null, name, GroupHistory.Action.MEMBER_JOINED, GroupHistory.details("member", name, "via", "link, as a guest"));
            return r.memberId();
        }
        UUID id = Uuid7.next();
        jdbc.sql("""
                        insert into group_members (id, group_id, user_id, display_name, role, email)
                        values (:id, :g, null, :name, 'MEMBER', :e)
                        """).param("id", id).param("g", r.groupId()).param("name", r.name()).param("e", r.email()).update();
        history.record(r.groupId(), null, r.name(), GroupHistory.Action.MEMBER_JOINED, GroupHistory.details("member", r.name(), "via", "link, as a guest"));
        return id;
    }

    /** The guest session's email; 401 guest-signed-out when there is none (or it ended). */
    public String session(String token) {
        if (token == null || token.isBlank()) {
            throw signedOut();
        }
        String email = jdbc.sql("""
                        update guest_sessions set last_seen_at = now()
                        where token_hash = :h and revoked_at is null and expires_at > now() returning email
                        """).param("h", hash(token)).query(String.class).optional().orElse(null);
        if (email == null) {
            throw signedOut();
        }
        return email;
    }

    @Transactional(readOnly = true)
    public Me me(String email) {
        List<GuestGroup> list = jdbc.sql("""
                        select g.id, g.name, g.emoji, g.currency, m.id as you from group_members m join groups g on g.id = m.group_id
                        where lower(m.email) = :e and m.user_id is null and m.left_at is null order by g.created_at desc
                        """).param("e", email).query(GuestGroup.class).list();
        String name = jdbc.sql("""
                        select display_name from group_members where lower(email) = :e and user_id is null and left_at is null
                        order by created_at desc limit 1
                        """).param("e", email).query(String.class).optional().orElse(null);
        return new Me(email, name, list);
    }

    public void signOut(String token) {
        if (token != null) {
            jdbc.sql("update guest_sessions set revoked_at = now() where token_hash = :h").param("h", hash(token)).update();
        }
    }

    private static ApiException signedOut() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "guest-signed-out", "open the group's link again to continue") {
        };
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
