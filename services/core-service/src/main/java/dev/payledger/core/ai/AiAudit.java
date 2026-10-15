package dev.payledger.core.ai;

import dev.payledger.core.common.Uuid7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * One row per call to a model (ai_requests): written in its own transaction, so a call that cost money is on record
 * even when what it was for fails or is rolled back. Texts are cleared after the retention period; numbers stay.
 */
@Component
public class AiAudit {

    private static final Logger log = LoggerFactory.getLogger(AiAudit.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate own;
    private final Duration textRetention;

    AiAudit(JdbcClient jdbc, PlatformTransactionManager tx, @Value("${payledger.ai.text-retention:180d}") Duration textRetention) {
        this.jdbc = jdbc;
        this.own = new TransactionTemplate(tx);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.textRetention = textRetention;
    }

    /** A call as it happened. {@code counted}: it uses one of the month's AI reads (Free). */
    public record Call(UUID userId, String feature, String channel, String provider, String model, String status, String error,
                       int inputTokens, int outputTokens, Double audioSeconds, long costMicros, long latencyMs, String inputText,
                       String outputText, boolean counted, Double modelConfidence, Double confidence, String outcome, Integer entries,
                       UUID capturedItemId) {
    }

    public UUID record(Call c) {
        UUID id = Uuid7.next();
        own.executeWithoutResult(s -> jdbc.sql("""
                        insert into ai_requests (id, user_id, feature, channel, provider, model, status, error, input_tokens, output_tokens,
                            audio_seconds, cost_micros, latency_ms, input_text, output_text, counted, model_confidence, confidence, outcome,
                            entries, captured_item_id)
                        values (:id, :u, :feature, :channel, :provider, :model, :status, :error, :in, :out, :audio, :cost, :latency, :input,
                            :output, :counted, :mc, :c, :outcome, :entries, :item)
                        """)
                .param("id", id).param("u", c.userId()).param("feature", c.feature()).param("channel", c.channel())
                .param("provider", cut(c.provider(), 30)).param("model", cut(c.model(), 80)).param("status", c.status())
                .param("error", cut(c.error(), 500)).param("in", c.inputTokens()).param("out", c.outputTokens()).param("audio", c.audioSeconds())
                .param("cost", c.costMicros()).param("latency", (int) Math.min(Integer.MAX_VALUE, c.latencyMs()))
                .param("input", c.inputText()).param("output", c.outputText()).param("counted", c.counted())
                .param("mc", c.modelConfidence()).param("c", c.confidence()).param("outcome", c.outcome()).param("entries", c.entries())
                .param("item", c.capturedItemId())
                .update());
        return id;
    }

    /** What we did with the answer, once we know: added (and as which transactions), left for review, or skipped. */
    public void outcome(UUID requestId, String outcome, Double confidence, List<UUID> transactionIds, UUID capturedItemId) {
        if (requestId == null) {
            return;
        }
        own.executeWithoutResult(s -> jdbc.sql("""
                        update ai_requests set outcome = :outcome, confidence = coalesce(:c, confidence),
                            transaction_ids = case when cast(:t as text) is null then transaction_ids else cast(string_to_array(cast(:t as text), ',') as uuid[]) end,
                            captured_item_id = coalesce(:item, captured_item_id)
                        where id = :id
                        """)
                .param("id", requestId).param("outcome", outcome).param("c", confidence)
                .param("t", transactionIds == null || transactionIds.isEmpty() ? null : String.join(",", transactionIds.stream().map(UUID::toString).toList()))
                .param("item", capturedItemId)
                .update());
    }

    /** The answer couldn't be used (not the JSON asked for). */
    public void invalid(UUID requestId, String error) {
        own.executeWithoutResult(t -> jdbc.sql("update ai_requests set status = 'INVALID', error = :e, outcome = 'FAILED' where id = :id")
                .param("e", cut(error, 500)).param("id", requestId).update());
    }

    /** Requests in the last minute: a person (or a script on their account) can't run up a bill. */
    public long lastMinute(UUID userId) {
        return jdbc.sql("select count(*) from ai_requests where user_id = :u and at > now() - interval '1 minute'")
                .param("u", userId).query(Long.class).single();
    }

    /** Daily: what was sent and answered is cleared after the retention period; tokens, cost and outcome stay. */
    @Scheduled(cron = "0 20 4 * * *", zone = "Asia/Kolkata")
    public void clearOldTexts() {
        int n = jdbc.sql("""
                        update ai_requests set input_text = null, output_text = null
                        where at < now() - cast(:days || ' days' as interval) and (input_text is not null or output_text is not null)
                        """)
                .param("days", String.valueOf(Math.max(1, textRetention.toDays()))).update();
        if (n > 0) {
            log.info("Cleared the texts of {} AI requests older than {} days", n, textRetention.toDays());
        }
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
