package dev.payledger.core.group;

import dev.payledger.core.common.ApiException;
import dev.payledger.core.group.GroupService.Doer;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The guest's door (no account): open a join link, prove the email, then see and add to the groups where that email
 * is a member. The gateway passes only the {@value #COOKIE} cookie here and no token; everything else stays behind sign-in.
 */
@RestController
@RequestMapping("/api/guest")
class GuestController {

    static final String COOKIE = "pl_guest";

    private final GuestService guests;
    private final GroupService groups;
    private final GroupExpenseService expenses;
    private final GroupComments comments;
    private final JdbcClient jdbc;
    private final boolean secure;

    GuestController(GuestService guests, GroupService groups, GroupExpenseService expenses, GroupComments comments, JdbcClient jdbc,
                    @Value("${payledger.cookie-secure:false}") boolean secure) {
        this.guests = guests;
        this.groups = groups;
        this.expenses = expenses;
        this.comments = comments;
        this.jdbc = jdbc;
        this.secure = secure;
    }

    record SendCode(UUID memberId, String name, String email) {
    }

    record Verify(String code) {
    }

    @GetMapping("/links/{token}")
    @Operation(summary = "A join link for someone without an account: the group and the names still waiting for their person")
    GuestService.Link link(@PathVariable String token) {
        return guests.link(token);
    }

    @PostMapping("/links/{token}/code")
    @Operation(summary = "Email a 6-digit code for joining as a waiting name (memberId) or a new one (name); 409 has-account, 429 too-many-codes")
    GuestService.CodeSent sendCode(@PathVariable String token, @RequestBody SendCode request) {
        return guests.sendCode(token, request.memberId(), request.name(), request.email());
    }

    @PostMapping("/codes/{codeId}")
    @Operation(summary = "Check the code; joins the group and starts a 30-day guest session (cookie)")
    ResponseEntity<GuestService.Joined> verify(@PathVariable UUID codeId, @RequestBody Verify request) {
        GuestService.Joined joined = guests.verify(codeId, request.code());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(joined.token(), GuestService.SESSION.toSeconds()).toString())
                // The token lives in the cookie only.
                .body(new GuestService.Joined(null, joined.groupId()));
    }

    @GetMapping("/me")
    @Operation(summary = "The guest: their email and their groups; 401 guest-signed-out without a session")
    GuestService.Me me(@CookieValue(name = COOKIE, required = false) String token) {
        return guests.me(guests.session(token));
    }

    @PostMapping("/sign-out")
    ResponseEntity<Void> signOut(@CookieValue(name = COOKIE, required = false) String token) {
        guests.signOut(token);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", 0).toString()).build();
    }

    // ---------------------------------------------------------------- a group, as the guest

    @GetMapping("/groups/{groupId}")
    GroupService.Group group(@CookieValue(name = COOKIE, required = false) String token, @PathVariable UUID groupId) {
        return groups.get(doer(token, groupId), groupId);
    }

    @GetMapping("/groups/{groupId}/activity")
    GroupExpenseService.Activity activity(@CookieValue(name = COOKIE, required = false) String token, @PathVariable UUID groupId) {
        return expenses.activity(doer(token, groupId), groupId);
    }

    @GetMapping("/groups/{groupId}/balances")
    GroupExpenseService.Balances balances(@CookieValue(name = COOKIE, required = false) String token, @PathVariable UUID groupId) {
        return expenses.balances(doer(token, groupId), groupId);
    }

    @GetMapping("/groups/{groupId}/insights")
    GroupExpenseService.Insights insights(@CookieValue(name = COOKIE, required = false) String token, @PathVariable UUID groupId) {
        return expenses.insights(doer(token, groupId), groupId);
    }

    @GetMapping("/groups/{groupId}/history")
    List<GroupHistory.Event> history(@CookieValue(name = COOKIE, required = false) String token, @PathVariable UUID groupId) {
        return expenses.history(doer(token, groupId), groupId);
    }

    @PostMapping("/groups/{groupId}/expenses")
    @ResponseStatus(HttpStatus.CREATED)
    GroupExpenseService.Expense create(@CookieValue(name = COOKIE, required = false) String token, @PathVariable UUID groupId,
                                       @Valid @RequestBody GroupExpenseRequests.Create request) {
        return expenses.create(doer(token, groupId), groupId, request);
    }

    @PutMapping("/groups/{groupId}/expenses/{expenseId}")
    @Operation(summary = "A guest edits an expense they added (403 not-yours otherwise)")
    GroupExpenseService.Expense replace(@CookieValue(name = COOKIE, required = false) String token, @PathVariable UUID groupId,
                                        @PathVariable UUID expenseId, @RequestParam int version,
                                        @Valid @RequestBody GroupExpenseRequests.Fields request) {
        Doer d = doer(token, groupId);
        own(d, "group_expenses", expenseId);
        return expenses.replace(d, groupId, expenseId, version, request);
    }

    @DeleteMapping("/groups/{groupId}/expenses/{expenseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@CookieValue(name = COOKIE, required = false) String token, @PathVariable UUID groupId, @PathVariable UUID expenseId,
                @RequestParam int version) {
        Doer d = doer(token, groupId);
        own(d, "group_expenses", expenseId);
        expenses.delete(d, groupId, expenseId, version);
    }

    @PostMapping("/groups/{groupId}/settlements")
    @ResponseStatus(HttpStatus.CREATED)
    GroupExpenseService.Settlement settle(@CookieValue(name = COOKIE, required = false) String token, @PathVariable UUID groupId,
                                          @Valid @RequestBody GroupExpenseRequests.Settle request) {
        return expenses.settle(doer(token, groupId), groupId, request);
    }

    @DeleteMapping("/groups/{groupId}/settlements/{settlementId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteSettlement(@CookieValue(name = COOKIE, required = false) String token, @PathVariable UUID groupId,
                          @PathVariable UUID settlementId) {
        Doer d = doer(token, groupId);
        own(d, "group_settlements", settlementId);
        expenses.deleteSettlement(d, groupId, settlementId);
    }

    record NewComment(String body) {
    }

    @GetMapping("/groups/{groupId}/comments")
    List<GroupComments.Comment> comments(@CookieValue(name = COOKIE, required = false) String token, @PathVariable UUID groupId) {
        return comments.list(doer(token, groupId), groupId);
    }

    @PostMapping("/groups/{groupId}/expenses/{expenseId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    GroupComments.Comment comment(@CookieValue(name = COOKIE, required = false) String token, @PathVariable UUID groupId,
                                  @PathVariable UUID expenseId, @RequestBody NewComment body) {
        return comments.add(doer(token, groupId), groupId, expenseId, body.body());
    }

    private Doer doer(String token, UUID groupId) {
        return groups.guest(guests.session(token), groupId);
    }

    /** Guests change only what they added themselves; the rest is for members with an account. */
    private void own(Doer d, String table, UUID id) {
        boolean mine = jdbc.sql("select exists (select 1 from " + table + " where id = :id and created_by_member = :m)")
                .param("id", id).param("m", d.memberId()).query(Boolean.class).single();
        if (!mine) {
            throw new ApiException(HttpStatus.FORBIDDEN, "not-yours", "guests can change only what they added") {
            };
        }
    }

    private ResponseCookie cookie(String value, long maxAge) {
        return ResponseCookie.from(COOKIE, value).httpOnly(true).secure(secure).sameSite("Lax").path("/api/guest").maxAge(maxAge).build();
    }
}
