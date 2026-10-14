package dev.payledger.core.admin;

import dev.payledger.core.admin.StaffRole.Permission;
import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The request log for staff (every API request: when to the millisecond, who, route, status, time taken, error), and CSV
 * exports of every log: requests, user activity, admin audit, emails and users. Exports are themselves audited.
 */
@RestController
class AdminLogsController {

    static final int MAX_EXPORT = 100_000;

    private final StaffService staff;
    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final ZoneId zone;

    AdminLogsController(StaffService staff, JdbcClient jdbc, AuditLog audit, @Value("${payledger.business-zone:Asia/Kolkata}") ZoneId zone) {
        this.staff = staff;
        this.jdbc = jdbc;
        this.audit = audit;
        this.zone = zone;
    }

    /** Filters shared by the list and its export. */
    record Filter(String status, String q, OffsetDateTime from, OffsetDateTime to) {

        String where() {
            return """
                    where (cast(:from as timestamptz) is null or r.at >= :from) and (cast(:to as timestamptz) is null or r.at < :to)
                      and (cast(:status as text) is null
                           or (:status = '2xx' and r.status < 400) or (:status = '4xx' and r.status between 400 and 499)
                           or (:status = '5xx' and r.status >= 500) or (:status = 'errors' and r.status >= 400))
                      and (cast(:q as text) is null or r.path ilike :like or r.route ilike :like or r.request_id = :q
                           or u.email ilike :like or r.error_code ilike :like or r.ip = :q)
                    """;
        }

        JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec) {
            String like = q == null ? null : "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            return spec.param("from", from).param("to", to).param("status", status).param("q", q).param("like", like);
        }
    }

    private static final String REQUEST_COLUMNS = """
            r.id, r.at, r.request_id, r.method, r.path, r.route, r.status, r.duration_ms, r.ip, r.user_agent,
            r.error_code, r.error_detail, r.user_id, u.email
            """;

    @GetMapping("/api/core/admin/requests")
    @Operation(summary = "Staff (audit view): every API request, newest first; status all|2xx|4xx|5xx|errors, q = path, route, email, error code, IP or request id; from/to as date-times")
    Map<String, Object> requests(CurrentUser user,
                                 @RequestParam(required = false) @Pattern(regexp = "2xx|4xx|5xx|errors") String status,
                                 @RequestParam(required = false) @Size(max = 200) String q,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
                                 @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit,
                                 @RequestParam(defaultValue = "0") @Min(0) int offset) {
        staff.require(user, Permission.AUDIT_VIEW);
        Filter f = new Filter(status, blank(q), from, to);
        long total = f.bind(jdbc.sql("select count(*) from request_log r left join users u on u.id = r.user_id " + f.where()))
                .query(Long.class).single();
        List<Map<String, Object>> rows = f.bind(jdbc.sql("select " + REQUEST_COLUMNS + " from request_log r left join users u on u.id = r.user_id "
                        + f.where() + " order by r.at desc limit :limit offset :offset"))
                .param("limit", limit).param("offset", offset).query().listOfRows();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("rows", rows);
        return result;
    }

    @GetMapping(value = "/api/core/admin/export/{kind}.csv", produces = "text/csv")
    @Operation(summary = "Staff: download a log as CSV (requests, activity, audit, emails, users), with the same filters as its list; up to 100,000 rows; audited")
    ResponseEntity<byte[]> export(CurrentUser user, @PathVariable @Pattern(regexp = "requests|activity|audit|emails|users") String kind,
                                  @RequestParam(required = false) @Pattern(regexp = "2xx|4xx|5xx|errors") String status,
                                  @RequestParam(required = false) @Size(max = 200) String q,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        Filter f = new Filter(status, blank(q), from, to);
        String sql;
        switch (kind) {
            case "requests" -> {
                staff.require(user, Permission.AUDIT_VIEW);
                sql = "select " + REQUEST_COLUMNS + " from request_log r left join users u on u.id = r.user_id " + f.where() + " order by r.at desc";
            }
            case "activity" -> {
                staff.require(user, Permission.USERS_VIEW);
                sql = """
                        select e.id, e.at, e.type, e.action, e.status, e.target::text as target, e.session_id, e.ip, e.user_agent, e.user_id, u.email
                        from user_events e join users u on u.id = e.user_id
                        where (cast(:from as timestamptz) is null or e.at >= :from) and (cast(:to as timestamptz) is null or e.at < :to)
                          and (cast(:q as text) is null or u.email ilike :like or e.action ilike :like or e.type = :q)
                          and (cast(:status as text) is null or e.status >= 400)
                        order by e.at desc
                        """;
            }
            case "audit" -> {
                staff.require(user, Permission.AUDIT_VIEW);
                sql = """
                        select a.id, a.at, a.action, a.actor_id, actor.email as actor_email, a.target_user_id, target.email as target_email, a.details::text as details
                        from admin_audit a join users actor on actor.id = a.actor_id left join users target on target.id = a.target_user_id
                        where (cast(:from as timestamptz) is null or a.at >= :from) and (cast(:to as timestamptz) is null or a.at < :to)
                          and (cast(:q as text) is null or a.action ilike :like or actor.email ilike :like or target.email ilike :like)
                        order by a.at desc
                        """;
            }
            case "emails" -> {
                staff.require(user, Permission.USERS_SUPPORT);
                sql = """
                        select e.id, e.created_at as at, e.kind, e.to_email, e.subject, e.sent_at, e.attempts, e.last_error
                        from email_outbox e
                        where (cast(:from as timestamptz) is null or e.created_at >= :from) and (cast(:to as timestamptz) is null or e.created_at < :to)
                          and (cast(:q as text) is null or e.to_email ilike :like or e.subject ilike :like or e.kind ilike :like)
                        order by e.created_at desc
                        """;
            }
            default -> {
                staff.require(user, Permission.USERS_VIEW);
                sql = """
                        select u.id, u.created_at as at, u.email, u.display_name, u.last_active_on, u.premium_source, u.premium_until,
                               u.trial_started_at, u.disabled_at, r.role as staff_role, u.locale, u.time_zone, u.currency
                        from users u left join staff_roles r on r.user_id = u.id
                        where (cast(:from as timestamptz) is null or u.created_at >= :from) and (cast(:to as timestamptz) is null or u.created_at < :to)
                          and (cast(:q as text) is null or u.email ilike :like or u.display_name ilike :like)
                        order by u.created_at desc
                        """;
            }
        }
        List<Map<String, Object>> rows = f.bind(jdbc.sql(sql + " limit " + MAX_EXPORT)).query().listOfRows();
        audit.record(user.id(), "export." + kind, null, Map.of("rows", rows.size(), "filter", String.valueOf(f)));
        String stamp = OffsetDateTime.now(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmm"));
        // A byte-order mark so Excel opens it as UTF-8 (₹, names in any script).
        byte[] body = ("﻿" + csv(rows)).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payledger-" + kind + "-" + stamp + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    /** RFC 4180, timestamps as ISO 8601 with milliseconds in the business time zone (2026-10-14T09:30:12.345+05:30). */
    private String csv(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return "no rows\r\n";
        }
        DateTimeFormatter iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
        StringBuilder sb = new StringBuilder(String.join(",", rows.getFirst().keySet())).append("\r\n");
        for (Map<String, Object> row : rows) {
            boolean first = true;
            for (Object v : row.values()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                String text = v == null ? "" : v instanceof Timestamp t ? t.toInstant().atZone(zone).format(iso)
                        : v instanceof OffsetDateTime o ? o.atZoneSameInstant(zone).format(iso) : v.toString();
                // Formula injection: a cell starting with = + - @ is opened by Excel as a formula.
                if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) {
                    text = "'" + text;
                }
                sb.append(text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("'")
                        ? "\"" + text.replace("\"", "\"\"") + "\"" : text);
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
