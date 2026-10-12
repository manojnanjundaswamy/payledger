package dev.payledger.core.space;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class SpaceQueries {

    private final JdbcClient jdbc;

    public SpaceQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Personal space first, then households by name. */
    @Transactional(readOnly = true)
    public List<SpaceSummary> spacesOf(UUID userId) {
        return jdbc.sql("""
                        select s.id, s.kind, s.name, s.currency, m.role
                        from space_members m
                        join spaces s on s.id = m.space_id
                        where m.user_id = :userId
                        order by s.kind = 'PERSONAL' desc, s.name
                        """)
                .param("userId", userId)
                .query(SpaceSummary.class)
                .list();
    }
}
