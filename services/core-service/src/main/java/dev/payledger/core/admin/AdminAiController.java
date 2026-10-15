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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI usage for staff: what every model call cost, for whom and why, and what came of it. Totals and trends are
 * aggregates (analytics); the request list shows the masked texts sent and the answers, so it needs the audit permission.
 */
@RestController
class AdminAiController {

    private final StaffService staff;
    private final JdbcClient jdbc;
    private final String provider;
    private final String model;

    AdminAiController(StaffService staff, JdbcClient jdbc, @Value("${payledger.ai.provider:anthropic}") String provider,
                      @Value("${payledger.ai.model:claude-haiku-5-5}") String model, @Value("${payledger.ai.api-key:}") String key) {
        this.staff = staff;
        this.jdbc = jdbc;
        this.provider = key.isBlank() ? "none" : provider;
        this.model = model;
    }

    /** One group's numbers: calls, tokens, cost (millionths of a dollar), and what came of them. */
    record Totals(long requests, long errors, long inputTokens, long outputTokens, long costMicros, long autoAdded, long toReview,
                  long notATransaction, long counted, Long avgLatencyMs) {
    }

    record ByKey(String key, long requests, long inputTokens, long outputTokens, long costMicros) {
    }

    record ByUser(UUID userId, String email, long requests, long inputTokens, long outputTokens, long costMicros, long counted, Instant last) {
    }

    record Day(LocalDate day, long requests, long costMicros) {
    }

    record Summary(String provider, String model, Totals totals, List<ByKey> features, List<ByKey> models, List<ByUser> users, List<Day> days) {
    }

    private static final String WHERE = """
            where (cast(:from as timestamptz) is null or r.at >= :from) and (cast(:to as timestamptz) is null or r.at < :to)
            """;

    @GetMapping("/api/core/admin/ai")
    @Operation(summary = "Staff: AI usage between two times (default: this month): totals, by feature, by model, by user, by day")
    Summary summary(CurrentUser user,
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        staff.require(user, Permission.ANALYTICS_VIEW);
        OffsetDateTime start = from != null ? from : OffsetDateTime.now().withDayOfMonth(1).toLocalDate().atStartOfDay().atOffset(OffsetDateTime.now().getOffset());
        Map<String, Object> p = new java.util.HashMap<>();
        p.put("from", start);
        p.put("to", to);
        Totals totals = jdbc.sql("""
                        select count(*) as requests, count(*) filter (where status <> 'OK') as errors,
                               coalesce(sum(input_tokens), 0) as input_tokens, coalesce(sum(output_tokens), 0) as output_tokens,
                               coalesce(sum(cost_micros), 0) as cost_micros,
                               count(*) filter (where outcome in ('AUTO_ADDED', 'PARTLY_ADDED')) as auto_added,
                               count(*) filter (where outcome = 'TO_REVIEW') as to_review,
                               count(*) filter (where outcome = 'NOT_A_TRANSACTION') as not_a_transaction,
                               count(*) filter (where counted) as counted, cast(avg(latency_ms) as bigint) as avg_latency_ms
                        from ai_requests r
                        """ + WHERE).params(p).query(Totals.class).single();
        List<ByKey> features = jdbc.sql("""
                        select feature as key, count(*) as requests, sum(input_tokens) as input_tokens, sum(output_tokens) as output_tokens,
                               sum(cost_micros) as cost_micros
                        from ai_requests r
                        """ + WHERE + " group by feature order by requests desc").params(p).query(ByKey.class).list();
        List<ByKey> models = jdbc.sql("""
                        select provider || ' · ' || model as key, count(*) as requests, sum(input_tokens) as input_tokens,
                               sum(output_tokens) as output_tokens, sum(cost_micros) as cost_micros
                        from ai_requests r
                        """ + WHERE + " group by provider, model order by requests desc").params(p).query(ByKey.class).list();
        List<ByUser> users = jdbc.sql("""
                        select r.user_id, u.email, count(*) as requests, sum(r.input_tokens) as input_tokens, sum(r.output_tokens) as output_tokens,
                               sum(r.cost_micros) as cost_micros, count(*) filter (where r.counted) as counted, max(r.at) as last
                        from ai_requests r left join users u on u.id = r.user_id
                        """ + WHERE + " group by r.user_id, u.email order by sum(r.cost_micros) desc, count(*) desc limit 200")
                .params(p).query(ByUser.class).list();
        List<Day> days = jdbc.sql("""
                        select cast(date_trunc('day', r.at) as date) as day, count(*) as requests, sum(r.cost_micros) as cost_micros
                        from ai_requests r
                        """ + WHERE + " group by 1 order by 1").params(p).query(Day.class).list();
        return new Summary(provider, model, totals, features, models, users, days);
    }

    record Request(UUID id, Instant at, UUID userId, String email, String feature, String channel, String provider, String model, String status,
                   String error, int inputTokens, int outputTokens, Double audioSeconds, long costMicros, Integer latencyMs, boolean counted,
                   Double modelConfidence, Double confidence, String outcome, Integer entries, UUID capturedItemId, String transactionIds,
                   String inputText, String outputText) {
    }

    @GetMapping("/api/core/admin/ai/requests")
    @Operation(summary = "Staff: AI requests, newest first, with the masked text sent and the answer (texts are cleared after the retention period)")
    List<Request> requests(CurrentUser user,
                           @RequestParam(required = false) UUID userId,
                           @RequestParam(required = false) @Pattern(regexp = "QUICK_ADD|CAPTURE|ASSISTANT|TRANSCRIBE") String feature,
                           @RequestParam(required = false) @Pattern(regexp = "OK|ERROR|INVALID|REFUSED") String status,
                           @RequestParam(required = false) @Size(max = 200) String q,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime before,
                           @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        staff.require(user, Permission.AUDIT_VIEW);
        Map<String, Object> p = new java.util.HashMap<>();
        p.put("user", userId);
        p.put("feature", feature);
        p.put("status", status);
        p.put("q", q == null || q.isBlank() ? null : q.strip());
        p.put("like", q == null ? null : "%" + q.strip() + "%");
        p.put("before", before);
        p.put("limit", limit);
        return jdbc.sql("""
                        select r.id, r.at, r.user_id, u.email, r.feature, r.channel, r.provider, r.model, r.status, r.error, r.input_tokens,
                               r.output_tokens, r.audio_seconds, r.cost_micros, r.latency_ms, r.counted, r.model_confidence, r.confidence, r.outcome,
                               r.entries, r.captured_item_id, array_to_string(r.transaction_ids, ' ') as transaction_ids, r.input_text, r.output_text
                        from ai_requests r left join users u on u.id = r.user_id
                        where (cast(:user as uuid) is null or r.user_id = :user) and (cast(:feature as text) is null or r.feature = :feature)
                          and (cast(:status as text) is null or r.status = :status) and (cast(:before as timestamptz) is null or r.at < :before)
                          and (cast(:q as text) is null or u.email ilike :like or r.outcome ilike :like or r.model ilike :like)
                        order by r.at desc limit :limit
                        """).params(p).query(Request.class).list();
    }
}
