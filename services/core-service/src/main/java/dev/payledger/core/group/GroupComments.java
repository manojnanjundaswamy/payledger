package dev.payledger.core.group;

import dev.payledger.core.common.ApiException;
import dev.payledger.core.common.InvalidRequestException;
import dev.payledger.core.common.NotFoundException;
import dev.payledger.core.common.Uuid7;
import dev.payledger.core.group.GroupService.Doer;
import dev.payledger.core.notify.Inbox;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Comments on a group's expenses (product plan 2.2, "Activity: comments on expenses"). Any member can comment, guests
 * too; only the writer can delete theirs. People in the expense with an account hear about it.
 */
@Service
public class GroupComments {

    private final JdbcClient jdbc;
    private final Inbox inbox;

    GroupComments(JdbcClient jdbc, Inbox inbox) {
        this.jdbc = jdbc;
        this.inbox = inbox;
    }

    public record Comment(UUID id, UUID expenseId, UUID memberId, String name, String body, Instant at, boolean you) {
    }

    /** The group's comments, oldest first (the newest 500). */
    @Transactional(readOnly = true)
    public List<Comment> list(Doer d, UUID groupId) {
        return jdbc.sql("""
                        select * from (
                          select c.id, c.expense_id, c.member_id, m.display_name as name, c.body, c.created_at as at, c.member_id = :me as you
                          from group_comments c join group_members m on m.id = c.member_id
                          where c.group_id = :g and c.deleted_at is null order by c.created_at desc limit 500) x
                        order by at
                        """).param("g", groupId).param("me", d.memberId()).query(Comment.class).list();
    }

    @Transactional
    public Comment add(Doer d, UUID groupId, UUID expenseId, String raw) {
        String body = raw == null ? "" : raw.strip();
        if (body.isEmpty() || body.length() > 500) {
            throw new InvalidRequestException("invalid-comment", "write something (up to 500 letters)");
        }
        String expense = jdbc.sql("select description from group_expenses where id = :e and group_id = :g and deleted_at is null")
                .param("e", expenseId).param("g", groupId).query(String.class).optional().orElseThrow(() -> new NotFoundException("expense"));
        UUID id = Uuid7.next();
        jdbc.sql("insert into group_comments (id, group_id, expense_id, member_id, body) values (:id, :g, :e, :m, :b)")
                .param("id", id).param("g", groupId).param("e", expenseId).param("m", d.memberId()).param("b", body).update();
        // Everyone in the expense with an account, and whoever commented on it before, except the writer.
        List<UUID> people = jdbc.sql("""
                        select distinct m.user_id from group_members m
                        where m.user_id is not null and m.left_at is null and m.id <> :me and (
                          m.id in (select member_id from group_expense_parts where expense_id = :e)
                          or m.id in (select member_id from group_comments where expense_id = :e and deleted_at is null))
                        """).param("me", d.memberId()).param("e", expenseId).query(UUID.class).list();
        String group = jdbc.sql("select name from groups where id = :g").param("g", groupId).query(String.class).single();
        String preview = body.length() > 120 ? body.substring(0, 117) + "…" : body;
        for (UUID to : people) {
            inbox.notify(to, Inbox.Kind.GROUP_EXPENSE, d.displayName() + " commented on " + expense + " in " + group, "“" + preview + "”",
                    "/groups/" + groupId + "?expense=" + expenseId, d.id());
        }
        return list(d, groupId).stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void delete(Doer d, UUID groupId, UUID commentId) {
        UUID writer = jdbc.sql("select member_id from group_comments where id = :c and group_id = :g and deleted_at is null")
                .param("c", commentId).param("g", groupId).query(UUID.class).optional().orElseThrow(() -> new NotFoundException("comment"));
        if (!writer.equals(d.memberId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "not-yours", "only the person who wrote it can delete it") {
            };
        }
        jdbc.sql("update group_comments set deleted_at = now() where id = :c").param("c", commentId).update();
    }
}
