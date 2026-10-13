package dev.payledger.core.space;

import dev.payledger.core.common.ApiException;
import dev.payledger.core.user.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Who may see and change a space's money. Every money endpoint goes through here first.
 * A space you are not a member of answers 404, not 403, so ids of other people's spaces leak nothing.
 */
@Service
public class SpaceAccess {

    private final JdbcClient jdbc;

    public SpaceAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Space read(CurrentUser user, UUID spaceId) {
        return find(user, spaceId);
    }

    /** Viewers can look but not change anything. */
    public Space write(CurrentUser user, UUID spaceId) {
        Space space = find(user, spaceId);
        if (space.role().equals("VIEWER")) {
            throw new ReadOnlyException();
        }
        return space;
    }

    private Space find(CurrentUser user, UUID spaceId) {
        return jdbc.sql("""
                        select s.id, s.kind, s.currency, m.role
                        from spaces s join space_members m on m.space_id = s.id
                        where s.id = :spaceId and m.user_id = :userId
                        """)
                .param("spaceId", spaceId)
                .param("userId", user.id())
                .query(Space.class)
                .optional()
                .orElseThrow(NotFoundException::new);
    }

    /** {@code ledgerBookRef} is how the ledger addresses this space's book. */
    public record Space(UUID id, String kind, String currency, String role) {
        public String ledgerBookRef() {
            return "space:" + id;
        }
    }

    static class NotFoundException extends ApiException {
        NotFoundException() {
            super(HttpStatus.NOT_FOUND, "space-not-found", "no such space, or you are not a member of it");
        }
    }

    static class ReadOnlyException extends ApiException {
        ReadOnlyException() {
            super(HttpStatus.FORBIDDEN, "space-read-only", "you can view this space but not change it");
        }
    }
}
