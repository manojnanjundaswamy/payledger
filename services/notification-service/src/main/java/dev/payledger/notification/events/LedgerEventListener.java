package dev.payledger.notification.events;

import dev.payledger.notification.feed.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class LedgerEventListener {

    private static final Logger log = LoggerFactory.getLogger(LedgerEventListener.class);

    private final JsonMapper json;
    private final NotificationService notifications;

    LedgerEventListener(JsonMapper json, NotificationService notifications) {
        this.json = json;
        this.notifications = notifications;
    }

    @KafkaListener(topics = "${payledger.events.ledger-topic}")
    void onLedgerEvent(String payload, @Header(name = "eventType", required = false) String eventType,
                       @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key) {
        if (eventType != null && !LedgerEntryPosted.TYPE.equals(eventType)) {
            log.debug("Ignoring event type {} (key {})", eventType, key);
            return;
        }
        LedgerEntryPosted event = json.readValue(payload, LedgerEntryPosted.class);
        if (event.schemaVersion() != LedgerEntryPosted.SUPPORTED_SCHEMA_VERSION) {
            throw new UnprocessableEventException("unsupported schemaVersion " + event.schemaVersion());
        }
        if (event.eventId() == null || event.postings() == null || event.postings().isEmpty()) {
            throw new UnprocessableEventException("event is missing eventId or postings");
        }
        int created = notifications.record(event);
        log.debug("Event {} (entry {}) -> {} new notification(s)", event.eventId(), event.entryId(), created);
    }
}
