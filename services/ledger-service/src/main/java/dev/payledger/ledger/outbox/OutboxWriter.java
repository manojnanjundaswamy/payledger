package dev.payledger.ledger.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * Appends an event to {@code outbox_events}.
 * <p>
 * {@link Propagation#MANDATORY}: an outbox row written in its own transaction would defeat the
 * whole pattern, so calling this outside the business transaction fails loudly.
 */
@Component
public class OutboxWriter {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public OutboxWriter(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String aggregateType, UUID aggregateId, UUID eventId, String eventType, Object payload) {
        jdbc.sql("""
                        insert into outbox_events (event_id, aggregate_type, aggregate_id, event_type, payload)
                        values (:eventId, :aggregateType, :aggregateId, :eventType, cast(:payload as jsonb))
                        """)
                .param("eventId", eventId)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("payload", json.writeValueAsString(payload))
                .update();
    }
}
